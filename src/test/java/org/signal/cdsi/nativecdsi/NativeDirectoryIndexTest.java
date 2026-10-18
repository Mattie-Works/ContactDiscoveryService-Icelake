/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signal.cdsi.enclave.DirectoryEntry;

class NativeDirectoryIndexTest {
  private static DirectoryEntry entry(long number) {
    return new DirectoryEntry(number, new byte[16], new byte[16], null);
  }

  @Test
  void appliesUpdatesAndDeletions() {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    index.apply(List.of(entry(123L)), false);
    assertEquals(1, index.size());
    assertTrue(index.find(123L).isPresent());
    index.apply(List.of(DirectoryEntry.deletionEntry(123L)), false);
    assertTrue(index.find(123L).isEmpty());
  }

  @Test
  void replacesDirectory() {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    index.apply(List.of(entry(1L)), false);
    index.apply(List.of(entry(2L)), true);
    assertTrue(index.find(1L).isEmpty());
    assertTrue(index.find(2L).isPresent());
  }

  @Test
  void copiesInputAndOutputArrays() {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    DirectoryEntry input = entry(1L);
    index.apply(List.of(input), false);
    input.aci()[0] = 7;
    DirectoryEntry output = index.find(1L).orElseThrow();
    assertEquals(0, output.aci()[0]);
    output.aci()[0] = 8;
    assertEquals(0, index.find(1L).orElseThrow().aci()[0]);
  }

  @Test
  void rejectsInvalidBatchWithoutApplyingEarlierItems() {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    DirectoryEntry invalid = new DirectoryEntry(2L, new byte[1], new byte[16], null);
    assertThrows(IllegalArgumentException.class,
        () -> index.apply(List.of(entry(1L), invalid), false));
    assertEquals(0, index.size());
  }
}
