/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.enclave;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Directory population contract independent of the backend.
 * An SGX-specific discovery session is intentionally NOT part of this API.
 */
public interface DirectoryEngine {
  /** Atomically apply a directory batch or replace the full directory. */
  CompletableFuture<Void> loadData(List<DirectoryEntry> entries, boolean clearAll);
}
