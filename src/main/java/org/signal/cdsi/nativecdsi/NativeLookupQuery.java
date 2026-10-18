/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import java.util.Arrays;

/** One requested E.164 with optional 16-byte unidentified access key. */
public record NativeLookupQuery(long e164, byte[] uak) {
  public NativeLookupQuery {
    if (uak != null && uak.length != 16)
      throw new IllegalArgumentException("UAK must be 16 bytes when present");
    uak = uak == null ? null : uak.clone();
  }

  @Override
  public byte[] uak() {
    return uak == null ? null : uak.clone();
  }
}
