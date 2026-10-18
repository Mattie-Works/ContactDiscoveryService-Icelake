/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.signal.cdsi.enclave.DirectoryEntry;

class NativeDirectoryEngineTest {
  @Test
  void existingPopulationContractAppliesAndClearsBatches() {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    NativeDirectoryEngine engine = new NativeDirectoryEngine(index);

    engine.loadData(List.of(new DirectoryEntry(101L, new byte[16], new byte[16], null)), false).join();
    assertEquals(1, index.size());

    engine.loadData(List.of(new DirectoryEntry(202L, new byte[16], new byte[16], null)), true).join();
    assertEquals(1, index.size());
    assertTrue(index.find(101L).isEmpty());
    assertTrue(index.find(202L).isPresent());

    assertThrows(CompletionException.class,
        () -> engine.loadData(List.of(new DirectoryEntry(0L, new byte[16], new byte[16], null)), false).join());
    assertEquals(1, index.size());
  }

  @Test
  void tokenCostsInDevelopmentAreSingleUse() {
    DevNativeTokenRateLimiter limiter = new DevNativeTokenRateLimiter();
    java.nio.ByteBuffer hash = java.nio.ByteBuffer.wrap(new byte[32]);
    limiter.prepare("user", 2, java.nio.ByteBuffer.wrap(new byte[32]), hash).join();
    assertEquals(2, limiter.validate("user", hash).join().intValue());
    assertThrows(CompletionException.class, () -> limiter.validate("user", hash).join());
  }
}
