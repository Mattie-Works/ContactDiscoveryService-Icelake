/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.enclave;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/** One request session. Native sessions must never claim to be attested. */
public interface DirectorySession {
  CompletableFuture<ByteBuffer> handshake(ByteBuffer request);
  CompletableFuture<ByteBuffer> rateLimit(ByteBuffer request);
  CompletableFuture<ByteBuffer> complete(ByteBuffer acknowledgement);
  CompletableFuture<Void> closeAsync();
}
