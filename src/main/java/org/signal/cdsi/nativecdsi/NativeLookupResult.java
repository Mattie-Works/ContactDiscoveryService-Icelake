/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

/**
 * Client-visible result. Missing numbers have null PNI and ACI.
 * ACI is disclosed only after matching the caller-supplied UAK.
 * No stored UAK is ever returned.
 */
public record NativeLookupResult(long e164, byte[] pni, byte[] aci) {
  public NativeLookupResult {
    pni = pni == null ? null : pni.clone();
    aci = aci == null ? null : aci.clone();
  }

  @Override
  public byte[] pni() { return pni == null ? null : pni.clone(); }

  @Override
  public byte[] aci() { return aci == null ? null : aci.clone(); }
}
