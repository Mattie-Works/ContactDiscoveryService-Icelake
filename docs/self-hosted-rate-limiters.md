# Self-hosted CDSI rate limiting: PostgreSQL + Redis

This guide describes the implementation on branch
[`feature/native-linux-cdsi`](https://github.com/Mattie-Works/ContactDiscoveryService-Icelake/tree/feature/native-linux-cdsi).
**Do not mistake this for the attested Signal CDSI deployment**: the native
discovery endpoint is a separate, non-attested custom-client protocol.

## Architecture

The original application distinguishes two kinds of limits:

| Operation | Existing backend | Self-hosted native mode |
| --- | --- | --- |
| Authenticate / limit new connections | Redis Cluster, `LeakyBucketRateLimiter` | Redis standalone for local development (or Redis Cluster) |
| Prepare/consume directory lookup token | Azure Cosmos DB, `TokenRateLimiter` | PostgreSQL, `PostgresTokenRateLimiter` |

The **Cosmos-backed limiter does not handle new connection throttling** in this
codebase. The PostgreSQL implementation uses a user-level database row lock and
transactions to serialize token redemptions across multiple CDSI instances.

### Safety properties

- Tokens are issued *before* charging lookup quota.
- Tokens are user-bound and identified by their 32-byte hash.
- Unknown, expired, or consumed tokens are **rejected**, not treated as free.
- Redeeming a prepared token uses a PostgreSQL transaction: update quota and
  delete token together, or roll back both if the limit is exceeded.
- Multiple instances contend on the same PostgreSQL bucket row (`FOR UPDATE`).
- Old-token cost carry-forward is supported, as in the Cosmos implementation.
- Pending tokens expire (5 minutes by default). Native session tokens have a
  shorter, 2-minute in-memory expiry.
- The standalone Redis limiter runs the existing atomic Lua leaky-bucket
  script and **fails closed** if Redis cannot be reached.
- A background cleanup removes expired pending tokens; snapshot/backup and
  per-user quota rows are retained.

**Differences from Cosmos:** Missing/consumed tokens are rejected rather than
treated as zero cost, and PostgreSQL pending-token expiry is explicitly
configured. Old Cosmos quota data is **not migrated automatically**. Plan a
maintenance-window cutover, assess quota resets, and invalidate or drain
outstanding sessions before switching backends.

## 1. Local development: Docker Compose

Create a local, ignored `.env` file with a fresh PostgreSQL password:

```sh
umask 077
printf 'CDSI_POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" > .env
```

Build and start the entire **local test stack**:

```sh
docker compose -f compose.native.dev.yml up -d --build
docker compose -f compose.native.dev.yml ps
docker compose -f compose.native.dev.yml logs -f cdsi
```

The Compose file starts:

- PostgreSQL 17 with a persistent named volume; applies
  `src/main/resources/db/postgres-token-rate-limit.sql` on *first database
  initialization*.
- Redis 7.4 standalone, with append-only persistence. Not publicly published.
- The `Dockerfile.native` CDSI image running with
  `MICRONAUT_ENVIRONMENTS=dev,native`, a random-account populator, and the
  PostgreSQL token limiter. Only CDSI ports **8080** and **8081** are published
  to `127.0.0.1`.

The `dev` Micronaut profile contains **test authentication credentials**. The
local Redis container has no password and is private to its Compose network.
**Never expose this Compose stack publicly or reuse its credentials in
production.**

Check schema initialization:

```sh
docker compose -f compose.native.dev.yml exec postgres \
  psql -U cdsi -d cdsi -c "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'cdsi_%';"
```

A failed health check should be diagnosed from the logs:

```sh
docker compose -f compose.native.dev.yml logs --tail=200 postgres redis cdsi
```

Stop the stack without deleting data:

```sh
docker compose -f compose.native.dev.yml down
```

**Destructive:** `docker compose -f compose.native.dev.yml down -v` removes
named database/Redis volumes; use only to deliberately reset test data.

## 2. Maven and integration tests

A local native build requires Java 25. The integration tests also require a
working Docker daemon for Testcontainers.

```sh
./mvnw -B -Pnative-linux \
  -Dtest='Native*Test,PostgresTokenRateLimiterIT,StandaloneRedisLeakyBucketRateLimiterIT' \
  test
```

The PostgreSQL integration tests verify successful charging, token replay
rejection, wrong-user rejection, expiry, carry-forward cost, transactional
rollback on insufficient credit, and concurrent redemption. The Redis
integration test verifies per-user connection rejection against a real
standalone Redis instance. They also run in
`.github/workflows/native-linux.yml`.

**Important:** The GitHub Actions integration tests have been added but are
not yet confirmed green. End-to-end WebSocket and data synchronization tests
are separate outstanding tasks.

## 3. Selecting limiters

Native mode uses `application-native.yml` to set:

```yaml
cdsi:
  backend: native
  token-limiter:
    backend: postgres
```

For PostgreSQL, supply:

```text
POSTGRES_TOKEN_RATE_LIMIT_JDBC_URL=jdbc:postgresql://postgres:5432/cdsi
POSTGRES_TOKEN_RATE_LIMIT_USERNAME=cdsi
POSTGRES_TOKEN_RATE_LIMIT_PASSWORD=(from a secure secret source)
POSTGRES_TOKEN_RATE_LIMIT_POOL_SIZE=10
```

The production lookup bucket capacity/leak rate use the existing
`tokenRateLimit.bucket-size`, `tokenRateLimit.leak-rate-scalar` and
`tokenRateLimit.leak-rate-duration` config (`application.yml`). Pending
token expiry can be changed with
`POSTGRES_TOKEN_RATE_LIMIT_TOKEN_TTL` (for example, `PT5M`); keep it longer
than the native WebSocket session's 2-minute token lifetime.

For standalone Redis connection limits:

```text
REDIS_LEAKY_BUCKET_MODE=standalone
REDIS_LEAKY_BUCKET_HOST=redis
REDIS_LEAKY_BUCKET_PORT=6379
REDIS_LEAKY_BUCKET_PASSWORD=(empty only in private local development)
```

For existing Redis Cluster deployments (legacy behavior), omit
`REDIS_LEAKY_BUCKET_MODE` or set `cluster`. The two implementations share
the same `LeakyBucketRateLimiter` interface.

The Cosmos implementation remains available to the old SGX deployment by
default (`cdsi.token-limiter.backend=cosmos`), but its configuration/beans
are disabled when PostgreSQL is selected.

Development-only `DevNativeTokenRateLimiter` is now explicitly opt-in with
`cdsi.token-limiter.backend=dev`; do **not** use it in production.

## 4. A production-like single-host template

`compose.native.production.example.yml` is a **deployment topology template**,
not a production approval. It keeps PostgreSQL/Redis inaccessible from the
public network, uses Redis authentication and persistent volumes, and binds
CDSI's HTTP port to localhost.

It expects these environment values (from a password manager/secret file):
`CDSI_POSTGRES_PASSWORD`, `CDSI_REDIS_PASSWORD`,
`CDSI_AUTH_SHARED_SECRET` (Base64-encoded 32 bytes shared with the external
Signal credential issuer), `CDSI_ACCOUNT_REGION`, `CDSI_ACCOUNT_TABLE`,
and `CDSI_ACCOUNT_STREAM`. The application's DynamoDB/Kinesis source still
requires valid AWS SDK credentials and reachability.

After provisioning, validate Compose before startup:

```sh
docker compose -f compose.native.production.example.yml config --quiet
docker compose -f compose.native.production.example.yml up -d --build
```

**Before real deployment:**

1. Supply a trusted HTTPS/WSS reverse proxy with an appropriate certificate;
   never publish backend HTTP directly.
2. Provision AWS DynamoDB/Kinesis (or implement/test a separate self-hosted
   directory synchronizer). PostgreSQL only replaces the *rate-limit store*;
   it does **not** replace the account directory's data source.
3. Install the SQL schema before first CDSI startup. The PostgreSQL Docker
   entrypoint processes `docker-entrypoint-initdb.d` **only for a fresh volume**.
   For an existing database, run the migration with `psql -v ON_ERROR_STOP=1 -f`
   using appropriate credentials.
4. Configure backups, restore exercises, data retention, metrics, resource
   quotas, and alerting. A single PostgreSQL/Redis container is **not HA**.
5. Restrict database credentials, configure TLS where transport is untrusted,
   and use external secret management rather than checked-in passwords.
6. Verify fail-closed behavior, quotas, load, synchronization, restart, and
   client interoperability under your actual production configuration.
7. Run the integration and end-to-end test suites. Avoid interpreting a built
   image as production-certified.

## 5. Database lifecycle

New installations: the schema is applied by Compose on fresh PostgreSQL data
volumes. Existing installations: apply
`src/main/resources/db/postgres-token-rate-limit.sql` explicitly.

The database contains `cdsi_token_bucket` (one row per user) and
`cdsi_token_cost` (hashed, unredeemed tokens). Pending entries carry an
expiration timestamp. The service removes expired tokens during preparation
and once per hour in the background.

Do **not** delete an active user's bucket to clean token data; that would
erase their active quota. Take a backup and use the appropriate maintenance
process for expiry or retention.

## Related documentation

- [Native build and Docker](native-linux-build-and-docker.md)
- [Non-attested custom-client protocol](native-custom-client-protocol.md)
- [Migration status](native-linux-migration.md)
