# Native Linux CDSI implementation status

## Chosen approach

The native backend is a **JVM-based Linux directory engine**, not a repackaged
SGX enclave or an Open Enclave simulation. No Intel SGX device or Open Enclave
runtime is needed to execute this backend. The trusted execution / ORAM privacy
guarantees are **not** reproduced. Standard Signal clients will not understand
the custom non-attested WebSocket protocol.

## Implemented in this branch

- `cdsi.backend=native` selects native beans and disables the legacy SGX
  WebSocket, enclave initialization, and SGX-specific health checks.
- `DirectoryEngine` is consumed by both DynamoDB/Kinesis and development
  account populators. `NativeDirectoryEngine` applies their updates to an
  in-memory `NativeDirectoryIndex` protected by a read/write lock.
- `NativeLookupService` enforces bounded requests, token lifetime, replay
  protection, account binding, and actual token-limiter `prepare/validate`.
- `NativeLookupResult` exposes PNI but only exposes ACI when the client's
  supplied UAK matches. Stored UAKs are not returned to the client.
- `/v2/native/discovery` is a separate, authenticated JSON WebSocket endpoint
  that explicitly announces `attested:false` and requires opt-in.
- Existing external-service authentication and per-user connection-limit checks
  remain in the authentication provider.
- `Dockerfile.native` and Maven `-Pnative-linux` bypass SGX/OE compilation
  and exclude signed enclaves and Open Enclave JNI .so resources from native
  builds. Source code for the **legacy** SGX backend remains in the repository.
- Native test cases and a GitHub Actions workflow have been added.

## Running a native build

```sh
./mvnw -Pnative-linux -Dtest='Native*Test' test
docker build -f Dockerfile.native -t cdsi-native .
```

Use `MICRONAUT_ENVIRONMENTS=dev,native` only in a development deployment.
Configure a real account table/stream, external service authentication secret,
PostgreSQL token limiter, and Redis connection limiter before any production use.
See [the PostgreSQL/Redis Docker Compose guide](self-hosted-rate-limiters.md).
Terminate WSS at a trusted proxy; never expose backend HTTP to the Internet.

See `docs/native-custom-client-protocol.md` for client frames and limitations.

## Outstanding completion criteria

- Verify Maven native compilation and unit tests with Java 25, and build/run
  the Docker image on Debian/Linux with no SGX device or Intel libraries.
- Perform a real account snapshot plus Kinesis update/delete synchronization
  test and verify lookup parity against expected directory data.
- Exercise authenticated WebSocket flows over WSS with custom clients, including
  rate exhaustion, replay, token expiry, malformed payloads, and restart.
- Perform concurrency, fuzzing, bounded-memory/load and failure-injection tests.
- Review authentication and rate-limit deployment configuration, TLS reverse
  proxy trust boundaries, privacy logging, retention, and monitoring.
- After a successful deployment decision, retire remaining legacy SGX source
  files/workflows if a **source-tree** removal is desired.

**Do not interpret the committed code as a tested or production-approved service.**
