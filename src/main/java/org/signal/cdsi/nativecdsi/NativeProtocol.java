/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

/**
 * Explicitly non-attested custom protocol. This message is NOT enclave
 * evidence. Clients MUST opt in; NEVER treat it as an SGX quote.
 */
public final class NativeProtocol {
  public static final String VERSION = "cdsi-native-v1";
  public static final String SECURITY_MODEL = "NON_ATTESTED_SERVER_TRUST";

  public static final String CAPABILITIES_JSON =
      "{\"type\":\"capabilities\",\"protocol\":\"cdsi-native-v1\","
          + "\"attested\":false,\"security_model\":\"NON_ATTESTED_SERVER_TRUST\"}";

  private NativeProtocol() {}
}
