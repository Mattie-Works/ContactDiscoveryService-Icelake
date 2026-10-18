# Native Linux CDSI migration

Status: design and initial migration plan. **Not yet a working native implementation.**

## Security contract

Native mode MUST NOT claim SGX attestation or emit fabricated SGX evidence. Clients must explicitly opt in to a new non-attested protocol version and authenticate the server using a normal authenticated transport (TLS). Server operators can access directory data and query contents; this is a different privacy model from CDSI enclaves.

## Existing dependencies

- `Enclave` loads `libjnishim-<enclaveId>.so` and a signed enclave image.
- `EnclaveClient` implements UNINITIALIZED -> ATTESTED -> RATELIMIT -> COMPLETE.
- `WebSocketHandler` sends `getEreport()` on connection and dispatches handshake/rate-limit/complete frames.
- The directory loader and metrics depend on `Enclave` native methods.
- The existing simulation flag does not remove Open Enclave.

## Migration stages

1. Introduce a versioned `/v2/native/discovery` endpoint with an explicit non-attested capability message; keep the existing v1 endpoint unchanged.
2. Define a `DirectoryEngine` abstraction for loading/updating directory records, opening sessions, and executing bounded lookups.
3. Extract the C directory logic into a normal Linux shared library with a JNI adapter. Remove Open Enclave headers and link flags from the **native target** only.
4. Preserve authenticated identity, per-user token/connection rate limits, request-size limits, lookup authorization, and bounded resource use.
5. Build a custom client that rejects native mode unless explicitly configured, and validates TLS credentials. Never silently downgrade from SGX.
6. Add interoperability, privacy-regression, concurrency, rate-limit, and restart/reload tests.
7. Only after a passing native build and integration tests, remove the old SGX build artifacts/dependencies if native-only deployment is desired.

## Acceptance criteria

- Native target builds on Debian without Intel SGX SDK, Open Enclave, enclave binaries, or SGX device nodes.
- Directory synchronization and ACI/PNI lookup behavior match the existing implementation.
- Invalid authentication, exhausted limits, malformed frames, and unsupported clients fail closed.
- No SGX quote or attestation claim is fabricated.
- Native-only Docker image launches and passes integration tests.

Do not deploy this branch as native CDSI until these criteria are met.
