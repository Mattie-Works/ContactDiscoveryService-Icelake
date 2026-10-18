/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.signal.cdsi.enclave.DirectoryEngine;
import org.signal.cdsi.enclave.DirectoryEntry;

/** Native backend for existing account snapshot and stream consumers. */
@Singleton
@Requires(property = "cdsi.backend", value = "native")
public final class NativeDirectoryEngine implements DirectoryEngine {
  private final NativeDirectoryIndex index;

  public NativeDirectoryEngine(NativeDirectoryIndex index) {
    this.index = Objects.requireNonNull(index);
  }

  @Override
  public CompletableFuture<Void> loadData(List<DirectoryEntry> entries, boolean clearAll) {
    try {
      index.apply(entries, clearAll);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
