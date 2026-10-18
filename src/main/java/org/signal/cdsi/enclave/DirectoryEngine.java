/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.enclave;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Host-facing directory engine contract.
 *
 * Implementations may use an SGX enclave or a native Linux process/library.
 * The transport and client protocol MUST independently identify whether
 * hardware attestation is available. Implementing this interface alone does
 * not confer SGX attestation or compatibility with existing Signal clients.
 */
public interface DirectoryEngine {

  /** Create a session for an authenticated rate-limit identity. */
  CompletableFuture<EnclaveClient> newClient(String rateLimitKey);

  /** Apply directory changes, optionally replacing the entire directory. */
  CompletableFuture<Void> loadData(List<DirectoryEntry> entries, boolean clearAll);
}
