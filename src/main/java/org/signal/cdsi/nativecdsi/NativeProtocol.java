/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import java.nio.charset.StandardCharsets;

/**
 * Non-attested custom-client protocol marker. This is NOT SGX evidence.
 * A native-mode client must explicitly opt in; never interpret this as an
 * enclave quote or silently downgrade an attested client.
 */
public final class NativeProtocol {
  public static final String VERSION = "cdsi-native-v1";
  public static final String SECURITY_MODEL = "NON_ATTESTED_SERVER_TRUST";
  public static final byte[] CAPABILITIES =
      ("{\"protocol\":\"cdsi-native-v1\",\"attested\":false,\"security_model\":\"NON_ATTESTED_SERVER_TRUST\"}")
          .getBytes(StandardCharsets.UTF_8);

  private NativeProtocol() { }
}
