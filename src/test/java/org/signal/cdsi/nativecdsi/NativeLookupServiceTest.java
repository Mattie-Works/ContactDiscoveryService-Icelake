/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.signal.cdsi.enclave.DirectoryEntry;
import org.signal.cdsi.limits.TokenRateLimiter;

class NativeLookupServiceTest {
  @Test
  void validatesBeforeReadingDirectory() {
    NativeDirectoryIndex directory = new NativeDirectoryIndex();
    directory.apply(List.of(new DirectoryEntry(123L, new byte[16], new byte[16], null)), false);
    AtomicInteger validations = new AtomicInteger();
    TokenRateLimiter limiter = new TokenRateLimiter() {
      public CompletableFuture<Void> prepare(String key, int count, ByteBuffer oldHash, ByteBuffer newHash) {
        assertEquals(32, newHash.remaining());
        return CompletableFuture.completedFuture(null);
      }
      public CompletableFuture<Integer> validate(String key, ByteBuffer hash) {
        validations.incrementAndGet();
        return CompletableFuture.completedFuture(0);
      }
    };
    NativeLookupService service = new NativeLookupService(directory, limiter, 10);
    byte[] token = service.prepare("user", 1).join();
    assertEquals(32, token.length);
    assertThrows(CompletionException.class, () -> service.lookup("user", token, List.of(123L)).join());
    assertEquals(1, validations.get());
  }

  @Test
  void rejectsUnauthenticatedAndOversizedRequests() {
    TokenRateLimiter limiter = new TokenRateLimiter() {
      public CompletableFuture<Void> prepare(String key, int count, ByteBuffer oldHash, ByteBuffer newHash) {
        return CompletableFuture.completedFuture(null);
      }
      public CompletableFuture<Integer> validate(String key, ByteBuffer hash) {
        return CompletableFuture.completedFuture(1);
      }
    };
    NativeLookupService service = new NativeLookupService(new NativeDirectoryIndex(), limiter, 1);
    assertThrows(IllegalArgumentException.class, () -> service.prepare("", 1));
    assertThrows(IllegalArgumentException.class, () -> service.prepare("user", 2));
    assertThrows(IllegalArgumentException.class,
        () -> service.lookup("user", new byte[32], List.of(1L, 2L)));
  }
}
