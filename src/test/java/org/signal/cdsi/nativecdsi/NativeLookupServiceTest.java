/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.signal.cdsi.enclave.DirectoryEntry;
import org.signal.cdsi.limits.TokenRateLimiter;

class NativeLookupServiceTest {
  private static final long E164 = 123456789L;

  private static final class TrackingLimiter implements TokenRateLimiter {
    private final Map<String, Integer> outstanding = new ConcurrentHashMap<>();
    int validations;

    private static String key(String user, ByteBuffer hash) {
      ByteBuffer copy = hash.duplicate();
      byte[] bytes = new byte[copy.remaining()];
      copy.get(bytes);
      return user + ":" + HexFormat.of().formatHex(bytes);
    }

    public CompletableFuture<Void> prepare(String user, int amount, ByteBuffer oldHash, ByteBuffer newHash) {
      if (outstanding.putIfAbsent(key(user, newHash), amount) != null)
        return CompletableFuture.failedFuture(new IllegalArgumentException("duplicate"));
      return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Integer> validate(String user, ByteBuffer tokenHash) {
      validations++;
      Integer amount = outstanding.remove(key(user, tokenHash));
      return amount == null
          ? CompletableFuture.failedFuture(new IllegalArgumentException("missing token"))
          : CompletableFuture.completedFuture(amount);
    }
  }

  private static NativeLookupService createService(TrackingLimiter limiter) {
    NativeDirectoryIndex index = new NativeDirectoryIndex();
    byte[] aci = new byte[16];
    byte[] pni = new byte[16];
    byte[] uak = new byte[16];
    aci[0] = 1;
    pni[0] = 2;
    uak[0] = 3;
    index.apply(List.of(new DirectoryEntry(E164, aci, pni, uak)), false);
    return new NativeLookupService(index, limiter, 10);
  }

  @Test
  void neverReturnsStoredUakAndDisclosesAciOnlyOnMatch() {
    TrackingLimiter limiter = new TrackingLimiter();
    NativeLookupService service = createService(limiter);

    byte[] anonymousToken = service.prepare("user", 1).join();
    var anonymous = service.lookup("user", anonymousToken,
        List.of(new NativeLookupQuery(E164, null))).join().get(0);
    assertNotNull(anonymous.pni());
    assertNull(anonymous.aci());

    byte[] badToken = service.prepare("user", 1).join();
    var unauthorized = service.lookup("user", badToken,
        List.of(new NativeLookupQuery(E164, new byte[16]))).join().get(0);
    assertNull(unauthorized.aci());

    byte[] goodUak = new byte[16];
    goodUak[0] = 3;
    byte[] goodToken = service.prepare("user", 1).join();
    var authorized = service.lookup("user", goodToken,
        List.of(new NativeLookupQuery(E164, goodUak))).join().get(0);
    assertEquals(1, authorized.aci()[0]);
    assertEquals(2, authorized.pni()[0]);
    assertEquals(3, limiter.validations);
  }

  @Test
  void replayedOrWrongIdentityOrCountTokensAreRejected() {
    TrackingLimiter limiter = new TrackingLimiter();
    NativeLookupService service = createService(limiter);
    byte[] token = service.prepare("user", 1).join();
    assertThrows(IllegalArgumentException.class, () ->
        service.lookup("other", token, List.of(new NativeLookupQuery(E164, null))));
    assertThrows(IllegalArgumentException.class, () ->
        service.lookup("user", token, List.of(new NativeLookupQuery(E164, null))));

    byte[] secondToken = service.prepare("user", 2).join();
    assertThrows(IllegalArgumentException.class, () ->
        service.lookup("user", secondToken, List.of(new NativeLookupQuery(E164, null))));
  }

  @Test
  void rejectsUnauthenticatedAndOversizedRequests() {
    NativeLookupService service = createService(new TrackingLimiter());
    assertThrows(IllegalArgumentException.class, () -> service.prepare("", 1));
    assertThrows(IllegalArgumentException.class, () -> service.prepare("user", 11));
    assertThrows(IllegalArgumentException.class, () ->
        service.lookup("user", new byte[32], List.of()));
  }

  @Test
  void directoryIsReadOnlyAfterLimiterAllowsRequest() {
    TrackingLimiter limiter = new TrackingLimiter() {
      @Override
      public CompletableFuture<Integer> validate(String user, ByteBuffer hash) {
        return CompletableFuture.completedFuture(0);
      }
    };
    NativeLookupService service = createService(limiter);
    byte[] token = service.prepare("user", 1).join();
    assertThrows(CompletionException.class, () ->
        service.lookup("user", token, List.of(new NativeLookupQuery(E164, null))).join());
  }
}
