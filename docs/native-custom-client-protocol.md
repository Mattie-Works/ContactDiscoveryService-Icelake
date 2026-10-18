# CDSI native Linux custom-client protocol (experimental)

**Security downgrade by design:** This protocol is not Intel SGX or Open Enclave, is not remotely attested, and is not compatible with standard Signal clients. The server operator can inspect directory records and queries. Clients MUST explicitly opt into native mode and authenticate the server using WSS/TLS. Never fabricate SGX quotes or automatically downgrade from an attested protocol.

## Endpoint and identity

- WebSocket URI: `wss://YOUR-CDSI-HOST/v2/native/discovery`
- Authenticate with the same Signal external-service credentials used by the existing CDSI server.
- The existing authentication provider validates credentials and applies its per-user connection rate limiter.
- Deployment MUST terminate TLS at a trusted proxy and disallow plaintext Internet access to backend port 8080.
- The native endpoint is enabled by setting `cdsi.backend=native`; it is disabled in default SGX mode.
- The service rejects requests until the configured account populator reports that its initial snapshot is complete and healthy.

## Protocol (UTF-8 JSON text WebSocket messages)

1. Server sends:
   ```json
   {"type":"capabilities","protocol":"cdsi-native-v1","attested":false,"security_model":"NON_ATTESTED_SERVER_TRUST"}
   ```
2. Client explicitly opts in, declaring the **exact** number of requested entries:
   ```json
   {"type":"accept","protocol":"cdsi-native-v1","count":2}
   ```
3. Server responds with a 32-byte random token (64 hexadecimal characters):
   ```json
   {"type":"token","token":"<64 hex characters>"}
   ```
4. Client submits the token **once** with exactly the declared number of phone numbers:
   ```json
   {"type":"query","token":"<64 hex characters>","entries":[
     {"e164":18005550101},
     {"e164":18005550102,"uak":"<32 hex characters>"}
   ]}
   ```
5. Server atomically claims the single-use token, enforces user/count/expiry bindings, validates the persistent token-rate-limit cost, and only then accesses directory records. Server replies:
   ```json
   {"type":"result","entries":[
     {"e164":18005550101,"pni":null,"aci":null},
     {"e164":18005550102,"pni":"<32 hex characters>","aci":null}
   ]}
   ```
   PNI and ACI are encoded as 16-byte hex values. ACI is only present if the client-supplied UAK matches the stored UAK; the stored UAK is **never** returned. The server closes the session after the result.

## Validation and limits

- Max WebSocket payload: 1 MiB; default maximum `count` is 1024 (configure `cdsi.native.max-numbers`).
- Tokens expire after two minutes and are single-use, user-bound, and count-bound.
- New tokens are prepared using `TokenRateLimiter.prepare`; spending is validated using `TokenRateLimiter.validate`.
- The production token limiter still needs a configured CosmosDB token bucket; the separate `DevNativeTokenRateLimiter` is only enabled in the development environment.
- A request rejected by the limiter returns a rate-limit WebSocket close; invalid inputs return close code 4003.
- Never log bearer tokens, UAKs, raw E.164 numbers, or response identifiers.

## Deployment and limitations

See `Dockerfile.native` and `src/main/resources/application-native.yml`. Directory snapshots and Kinesis updates are delivered to `NativeDirectoryEngine` by the existing `AccountPopulator` implementations. In development, the random-account populator is enabled by the `dev` Micronaut environment.

The implementation uses a regular **JVM in-memory hash table on Linux** and does not preserve SGX's ORAM or trusted execution guarantees. It preserves CDSI-style PNI lookup and UAK-gated ACI disclosure, but its network format and operational trust model are purposefully different. This is not Signal's production CDSI protocol.

**Status:** Source implementation committed; full build, deployment, load, interoperability, and security testing are still required before production use.
