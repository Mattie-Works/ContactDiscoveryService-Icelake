/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.signal.cdsi.enclave.DirectoryEntry;

/**
 * Host-memory directory index for the non-attested backend.
 * Not oblivious: operators can observe stored data and access patterns.
 * This is an internal storage component, not a client protocol or a rate limiter.
 */
public final class NativeDirectoryIndex {
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Map<Long, DirectoryEntry> records = new HashMap<>();

  /** Applies a batch atomically with respect to concurrent readers. */
  public void apply(List<DirectoryEntry> updates, boolean clearAll) {
    if (updates == null) {
      throw new IllegalArgumentException("updates must not be null");
    }
    // Validate before taking the write lock to avoid partial application.
    for (DirectoryEntry entry : updates) {
      if (entry == null || entry.e164() <= 0 || entry.aci() == null || (!entry.isDeletion() &&
          (entry.aci().length != 16 || entry.pni() == null || entry.pni().length != 16 ||
              (entry.uak() != null && entry.uak().length != 0 && entry.uak().length != 16)))) {
        throw new IllegalArgumentException("invalid directory entry");
      }
    }
    lock.writeLock().lock();
    try {
      if (clearAll) records.clear();
      for (DirectoryEntry entry : updates) {
        if (entry.isDeletion()) {
          records.remove(entry.e164());
        } else {
          records.put(entry.e164(), copy(entry));
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Internal lookup; only invoke after authenticating and authorizing a request. */
  public Optional<DirectoryEntry> find(long e164) {
    lock.readLock().lock();
    try {
      return Optional.ofNullable(records.get(e164)).map(NativeDirectoryIndex::copy);
    } finally {
      lock.readLock().unlock();
    }
  }

  public int size() {
    lock.readLock().lock();
    try {
      return records.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  private static DirectoryEntry copy(DirectoryEntry entry) {
    return new DirectoryEntry(entry.e164(), Arrays.copyOf(entry.aci(), entry.aci().length),
        Arrays.copyOf(entry.pni(), entry.pni().length),
        entry.uak() == null ? null : Arrays.copyOf(entry.uak(), entry.uak().length));
  }
}
