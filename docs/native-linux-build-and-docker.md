> **Self-hosted local stack:** For a working PostgreSQL + Redis setup, use
> [the dedicated rate-limiter and Compose guide](self-hosted-rate-limiters.md).
> A plain `docker run` without database configuration is not sufficient when
> native mode selects PostgreSQL token quotas.
>
> ```sh
> umask 077
> printf 'CDSI_POSTGRES_PASSWORD=%s\\n' "$(openssl rand -hex 32)" > .env
> docker compose -f compose.native.dev.yml up -d --build
> ```
>
# Native Linux CDSI: build, Docker image, and local deployment

This guide applies to the **`feature/native-linux-cdsi`** branch of
[ContactDiscoveryService-Icelake](https://github.com/Mattie-Works/ContactDiscoveryService-Icelake).
It documents the existing [`Dockerfile.native`](../Dockerfile.native) and the
Maven **`native-linux`** profile. These are separate from the legacy SGX image
and build.

> **Experimental / non-attested:** Native mode uses a Linux-hosted JVM directory
> and does **not** provide Intel SGX/Open Enclave attestation, SGX memory
> protection, or ORAM privacy. It requires a **custom client** speaking
> `/v2/native/discovery`; unmodified Signal CDSI clients are incompatible.
> Build or container startup success is **not** a production security approval.

## 1. Prerequisites

For a local Maven build:

- Linux (Debian/Ubuntu recommended).
- **JDK 25** (`java -version` and `javac -version`).
- Git; Maven **3.9.9+** is required by the project, but the included
  `./mvnw` wrapper can install/use Maven for you.
- Network access to Maven repositories for initial dependency resolution.

For the Docker image, **Docker Engine with BuildKit** is sufficient; the
multistage image supplies JDK 25 and Maven runs through `./mvnw` inside the
build stage. No SGX device, Intel SGX driver, Open Enclave SDK, or host JDK is
required to build the native Docker image.

Run commands below **from the repository root** (where `pom.xml`,
`mvnw`, and `Dockerfile.native` are located).

## 2. Get the native branch

Fresh checkout:

```bash
git clone --branch feature/native-linux-cdsi \
  https://github.com/Mattie-Works/ContactDiscoveryService-Icelake.git
cd ContactDiscoveryService-Icelake
```

Existing checkout:

```bash
git fetch origin
git switch feature/native-linux-cdsi
git pull --ff-only origin feature/native-linux-cdsi
```

Verify:

```bash
git branch --show-current
# feature/native-linux-cdsi
```

## 3. Compile and test natively (without SGX)

```bash
chmod +x ./mvnw
java -version
./mvnw -B -Pnative-linux -Dtest='Native*Test' clean test
```

The `native-linux` profile disables the Open Enclave test-build execution and
excludes enclave-signed/JNI shared-object resources. It does **not** delete the
legacy SGX source tree.

Inspect failing tests:

```bash
grep -R -n -E '<<< FAILURE!|<<< ERROR!|Tests run:' \
  target/surefire-reports/*.txt
cat target/surefire-reports/org.signal.cdsi.nativecdsi.NativeLookupServiceTest.txt
```

Build the application without executing tests:

```bash
./mvnw -B -Pnative-linux -DskipTests clean package
```

To run locally with a Java classpath instead of Docker, copy runtime
dependencies as well:

```bash
./mvnw -B -Pnative-linux -DskipTests package \
  dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory=target/runtime-libs

MICRONAUT_ENVIRONMENTS=dev,native \
  java -cp 'target/classes:target/runtime-libs/*' \
  org.signal.cdsi.Application
```

**Note:** `-DskipTests` skips execution, so the application build does not
prove that native unit or integration tests passed.

## 4. Build the native Docker image

```bash
docker build --progress=plain \
  --file Dockerfile.native \
  --tag cdsi-native:dev \
  .
```

The last dot is the build context (repository root). `Dockerfile.native`
currently:

1. Uses `eclipse-temurin:25-jdk-jammy` in the build stage.
2. Runs `./mvnw -Pnative-linux -DskipTests package dependency:copy-dependencies`.
3. Copies compiled classes and runtime libraries to a Java 25 JRE image.
4. Runs as non-root UID **10000**, exposing application port **8080**.
5. Sets `MICRONAUT_ENVIRONMENTS=native` by default.

The native image does **not** execute the SGX runtime-library installation step
from the legacy `Dockerfile`. Build from `Dockerfile.native` explicitly;
`docker build .` alone targets the **legacy SGX Dockerfile**.

To rebuild after code changes:

```bash
git pull --ff-only origin feature/native-linux-cdsi
docker build --file Dockerfile.native --tag cdsi-native:dev .
```

Use `--no-cache` only when troubleshooting stale cached layers:

```bash
docker build --no-cache --progress=plain \
  --file Dockerfile.native --tag cdsi-native:dev .
```

Check the result:

```bash
docker image ls cdsi-native
docker image inspect cdsi-native:dev --format '{{.Id}}'
```

## 5. Start a local **development-only** container

```bash
docker run --detach \
  --name cdsi-native \
  --restart unless-stopped \
  --publish 127.0.0.1:8080:8080 \
  --publish 127.0.0.1:8081:8081 \
  --env MICRONAUT_ENVIRONMENTS=dev,native \
  --env RANDOM_ACCOUNT_POPULATOR_ACCOUNTS=1000 \
  cdsi-native:dev
```

`dev,native` activates both
[`application-dev.yml`](../src/main/resources/application-dev.yml) and
[`application-native.yml`](../src/main/resources/application-native.yml).
The dev profile provides a **test authentication secret** and uses a
development token limiter; **never use these settings on the public Internet**.

The optional `RANDOM_ACCOUNT_POPULATOR_ACCOUNTS` variable corresponds to
`random-account-populator.accounts` and requests 1,000 generated example
directory entries. These entries are **not durable** across process restarts.

Host ports are bound to `127.0.0.1` to prevent accidental public exposure.
Port **8080** is the application WebSocket/HTTP port. Port **8081** is configured
as the Micronaut management endpoint port by `application.yml`; only publish
it locally, and restrict it further in a real deployment.

**Important startup caveat:** the repository also contains AWS DynamoDB/Kinesis
population and Cosmos limiter beans. Whether the `dev,native` combination
starts without those services depends on the current bean conditions and
external configuration. Check the logs and health before assuming that the
container is operational. This guide does not claim that the configuration
has passed an end-to-end startup test.

## 6. Check the running container

```bash
docker ps --filter name=cdsi-native
docker logs --tail 200 --follow cdsi-native
docker exec cdsi-native java -version
docker exec cdsi-native printenv MICRONAUT_ENVIRONMENTS
```

If management endpoints initialize, check health locally:

```bash
curl --fail-with-body -i http://127.0.0.1:8081/health
```

An HTTP response confirms only that the management endpoint responded;
readiness should depend on successful initial account population. A native
discovery request must be an **authenticated WebSocket** exchange, not a
plain HTTP GET:

```text
ws://127.0.0.1:8080/v2/native/discovery    (local development only)
wss://your-cdsi-host/v2/native/discovery   (trusted TLS proxy required)
```

See [the native protocol specification](native-custom-client-protocol.md) and
[`tools/native_cdsi_client.py`](../tools/native_cdsi_client.py) for the
explicit non-attested client handshake. The reference client requires
`websockets>=14` and intentionally insists on `wss://`, so use it against
a TLS-enabled endpoint instead of the raw local `ws://` port.

## 7. Stop, remove, and update the container

```bash
docker stop cdsi-native
docker rm cdsi-native
```

After rebuilding `cdsi-native:dev`, repeat the development `docker run`
command in section 5. For a one-line cleanup:

```bash
docker rm --force cdsi-native
```

This removes only the container, not the locally built image.

## 8. Tag, export, import, or push an image

Assign a version tag:

```bash
docker tag cdsi-native:dev cdsi-native:0.1.0
```

Move the image to another Debian/Linux host without a registry:

```bash
docker save --output cdsi-native-0.1.0.tar cdsi-native:0.1.0
# Copy the tar archive securely to the destination host, then run:
docker load --input cdsi-native-0.1.0.tar
```

Or, after authenticating to **your own** container registry:

```bash
docker tag cdsi-native:dev registry.example.com/cdsi/native:0.1.0
docker push registry.example.com/cdsi/native:0.1.0
```

Replace `registry.example.com` and the tag with your real registry and version.
Neither pushing nor exporting publishes a usable Signal-compatible client
protocol.

## 9. Production configuration requirements (not yet validated)

Do not deploy with `MICRONAUT_ENVIRONMENTS=dev,native` in production. Native
mode requires the existing application's production integrations and secrets:

| Dependency | Config prefix / role |
| --- | --- |
| CDSI backend | `cdsi.backend=native` (set by the `native` environment) |
| Custom lookup bound | `cdsi.native.max-numbers` (default 1024) |
| Authentication | `authentication.sharedSecret` (Base64-encoded 32-byte secret) |
| Account snapshot/update stream | `accountTable.*`, DynamoDB and Kinesis connectivity |
| Token quotas | `cosmos.*` and `tokenRateLimit.*` |
| Connection quotas | `redis-leaky-bucket.*` and `leakyBucketLimit.connections.*` |
| Network security | Valid HTTPS/WSS certificate, authenticated client, trusted reverse proxy |
| Operational readiness | Complete initial directory population; healthy update consumer |

Supply configuration through Micronaut-supported environment variables or an
external configuration file; **do not bake secrets into the Docker image**.
Configure cloud/Redis credentials and DNS/network paths for your deployment.

For a local file-based configuration, supply an external Micronaut YAML file
and select it explicitly (example path only):

```bash
docker run --detach --name cdsi-native \
  --publish 127.0.0.1:8080:8080 \
  --env MICRONAUT_ENVIRONMENTS=native \
  --env MICRONAUT_CONFIG_FILES=/config/application.yml \
  --volume /secure/path/application.yml:/config/application.yml:ro \
  cdsi-native:dev
```

The example will start successfully **only if** that config file includes
the necessary service settings and the dependent services are reachable.
Ensure the mounted file is readable by UID 10000. Secrets also need appropriate
host file permissions or a dedicated secret-management mechanism.

## 10. Troubleshooting

| Symptom | Check |
| --- | --- |
| `java: release version 25 not supported` | Install/choose JDK 25 for local Maven builds; Docker's builder already uses JDK 25. |
| Maven launches Open Enclave `make` or SGX signing | Confirm `-Pnative-linux`, check `./mvnw help:active-profiles`, and use `Dockerfile.native` rather than `Dockerfile`. |
| Docker build cannot download dependencies | Verify DNS, proxy, network, Maven Central access, and Docker BuildKit connectivity. |
| No container in `docker ps` | Inspect `docker ps -a` and `docker logs cdsi-native`. |
| Bean creation/configuration exception | Confirm active environments and required authentication, account, Cosmos, and Redis settings. |
| Readiness is DOWN | Check initial account snapshot and stream errors in logs. |
| WebSocket returns 401 | Verify the external-service credentials and authentication configuration. |
| `/v1/{enclaveId}/discovery` unavailable | Native mode intentionally disables this SGX-only endpoint; use the separate `/v2/native/discovery` custom protocol. |
| Client expects SGX attestation | Use a purpose-built, explicitly non-attested client; there is no silent compatibility mode. |
| Test fails | Inspect `target/surefire-reports/*.txt` and the GitHub Actions native test job. |

For full Maven failure details:

```bash
./mvnw -B -e -Pnative-linux -Dtest='Native*Test' \
  -DtrimStackTrace=false clean test
```

For the Linux container's build failure, run the `docker build` command in
section 4 with `--progress=plain` and preserve the first error message.

## Related files

- [Native migration status](native-linux-migration.md)
- [Custom-client protocol](native-custom-client-protocol.md)
- [Native Dockerfile](../Dockerfile.native)
- [Native environment configuration](../src/main/resources/application-native.yml)
- [Native GitHub Actions workflow](../.github/workflows/native-linux.yml)
- [Custom Python client](../tools/native_cdsi_client.py)
