# Native CDSI custom-client protocol (draft; NOT deployed)

This document describes a **new**, non-attested protocol. The existing `/v1/{enclaveId}/discovery` endpoint is SGX-specific and MUST NOT be reused with fabricated enclave evidence.

## Security model

- Server authentication and confidentiality depend on TLS. The operator can access directory contents and request patterns.
- Custom clients MUST explicitly enable native mode. Never downgrade automatically from SGX.
- The first server message must explicitly contain `protocol: cdsi-native-v1`, `attested: false`, and `security_model: NON_ATTESTED_SERVER_TRUST`.
- Client requests must use authenticated identity and bounded payloads. Apply existing connection limits before allocating lookup state.
- Reject unknown protocol versions, malformed frames, mismatched token/request counts, and replayed tokens.
- Do not log raw phone numbers, identifiers, lookup results, or bearer tokens.

## Required handshake sequence (proposed)

1. Establish authenticated WSS connection to a **separate** native endpoint.
2. Server sends a versioned non-attestation capability frame.
3. Client explicitly acknowledges native mode and declares lookup count.
4. Server prepares a single-use rate-limit token and sends it to the client.
5. Client submits the token and exactly the declared number of E.164 entries.
6. Server atomically redeems the token and enforces permitted cost before reading directory entries.
7. Server sends the bounded result and closes the session.

## Not implemented yet

No native WebSocket handler, session framing, TLS binding, or client exists in this branch. The existing `NativeLookupService` is an internal prototype only. Its token semantics and the backing rate limiter require validation before external exposure. A normal Linux native C/JNI engine and a SGX-free build also remain outstanding.
