/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.signal.cdsi.enclave.DirectoryEntry;
import org.signal.cdsi.limits.TokenRateLimiter;

/**
 * Non-attested custom-client lookups.
 *
 * The caller MUST authenticate the identity and apply connection-rate limits.
 * Prepared tokens are single-use, user-bound, count-bound, expire after two minutes,
 * and are bounded in memory. The persistent rate limiter is still authoritative.
 * Results never contain stored UAKs and expose ACI only when the provided UAK matches.
 */
public final class NativeLookupService {
  private static final int TOKEN_BYTES = 32;
  private static final Duration TOKEN_TTL = Duration.ofMinutes(2);
  private static final int DEFAULT_MAX_PENDING = 10_000;

  private record Pending(String user, int count, Instant expiresAt) {}

  private final NativeDirectoryIndex directory;
  private final TokenRateLimiter limiter;
  private final SecureRandom random = new SecureRandom();
  private final int maxNumbers;
  private final Clock clock;
  private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final Semaphore pendingCapacity;

  public NativeLookupService(NativeDirectoryIndex directory, TokenRateLimiter limiter, int maxNumbers) {
    this(directory, limiter, maxNumbers, Clock.systemUTC(), DEFAULT_MAX_PENDING);
  }

  NativeLookupService(NativeDirectoryIndex directory, TokenRateLimiter limiter, int maxNumbers,
      Clock clock, int maxPending) {
    this.directory = Objects.requireNonNull(directory);
    this.limiter = Objects.requireNonNull(limiter);
    this.clock = Objects.requireNonNull(clock);
    if (maxNumbers < 1 || maxPending < 1)
      throw new IllegalArgumentException("limits must be positive");
    this.maxNumbers = maxNumbers;
    this.pendingCapacity = new Semaphore(maxPending);
  }

  /** Prepare a one-time token, returned to the client before rate-limit consumption. */
  public CompletableFuture<byte[]> prepare(String authenticatedUser, int count) {
    requireUser(authenticatedUser);
    if (count < 1 || count > maxNumbers) throw new IllegalArgumentException("invalid query size");

    evictExpired();
    if (!pendingCapacity.tryAcquire())
      throw new IllegalStateException("too many outstanding native requests");

    byte[] token = new byte[TOKEN_BYTES];
    random.nextBytes(token);
    byte[] tokenHash = sha256(token);
    String key = HexFormat.of().formatHex(tokenHash);

    final CompletableFuture<Void> prepared;
    try {
      prepared = limiter.prepare(authenticatedUser, count,
          ByteBuffer.wrap(new byte[TOKEN_BYTES]), ByteBuffer.wrap(tokenHash.clone()));
    } catch (RuntimeException | Error t) {
      pendingCapacity.release();
      throw t;
    }

    return prepared.handle((ignored, error) -> {
      if (error != null) {
        pendingCapacity.release();
        throw new java.util.concurrent.CompletionException(error);
      }
      Pending old = pending.putIfAbsent(key,
          new Pending(authenticatedUser, count, clock.instant().plus(TOKEN_TTL)));
      if (old != null) {
        pendingCapacity.release();
        throw new IllegalStateException("unexpected token collision");
      }
      return token.clone();
    });
  }

  /**
   * Single-use redemption. A token cannot be replayed, used by another user, or
   * redeemed for a different number of lookups. Validation must complete before
   * reading directory information.
   */
  public CompletableFuture<List<NativeLookupResult>> lookup(
      String authenticatedUser, byte[] token, List<NativeLookupQuery> queries) {
    requireUser(authenticatedUser);
    Objects.requireNonNull(queries);
    if (queries.isEmpty() || queries.size() > maxNumbers || queries.contains(null))
      throw new IllegalArgumentException("invalid query size");
    if (token == null || token.length != TOKEN_BYTES)
      throw new IllegalArgumentException("invalid token");

    byte[] tokenHash = sha256(token);
    Pending prepared = pending.remove(HexFormat.of().formatHex(tokenHash));
    if (prepared == null)
      throw new IllegalArgumentException("unknown or already consumed token");
    pendingCapacity.release();

    if (!prepared.user().equals(authenticatedUser) || prepared.count() != queries.size()
        || !clock.instant().isBefore(prepared.expiresAt()))
      throw new IllegalArgumentException("token identity, count or expiration mismatch");

    return limiter.validate(authenticatedUser, ByteBuffer.wrap(tokenHash.clone()))
        .thenApply(permitsUsed -> {
          if (permitsUsed == null || permitsUsed < queries.size())
            throw new IllegalStateException("insufficient validated permits");
          List<NativeLookupResult> result = new ArrayList<>(queries.size());
          for (NativeLookupQuery query : queries) {
            DirectoryEntry entry = directory.find(query.e164()).orElse(null);
            if (entry == null) {
              result.add(new NativeLookupResult(query.e164(), null, null));
              continue;
            }
            byte[] suppliedUak = query.uak();
            byte[] storedUak = entry.uak();
            byte[] authorizedAci = suppliedUak != null && storedUak != null
                && storedUak.length == 16 && MessageDigest.isEqual(suppliedUak, storedUak)
                    ? entry.aci() : null;
            result.add(new NativeLookupResult(query.e164(), entry.pni(), authorizedAci));
          }
          return List.copyOf(result);
        });
  }

  private void evictExpired() {
    Instant now = clock.instant();
    pending.forEach((key, value) -> {
      if (!now.isBefore(value.expiresAt()) && pending.remove(key, value))
        pendingCapacity.release();
    });
  }

  private static byte[] sha256(byte[] token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void requireUser(String user) {
    if (user == null || user.isBlank())
      throw new IllegalArgumentException("authenticated user required");
  }
}
