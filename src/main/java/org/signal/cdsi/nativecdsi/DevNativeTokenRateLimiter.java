/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Primary;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.signal.cdsi.limits.TokenRateLimiter;

/**
 * Development-only token limiter. It retains token costs, so native development
 * exercises the authorization/charging path unlike AllowAllTokenRateLimiter,
 * which always returns zero. NOT a production rate limiter or quota store.
 */
@Singleton
@Primary
@Requires(env = "dev")
@Requires(property = "cdsi.backend", value = "native")
@Requires(property = "cdsi.token-limiter.backend", value = "dev")
public final class DevNativeTokenRateLimiter implements TokenRateLimiter {
  private final Map<String, Integer> tokens = new ConcurrentHashMap<>();

  private static String key(String user, ByteBuffer token) {
    byte[] raw = new byte[token.remaining()];
    token.duplicate().get(raw);
    return user + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  @Override
  public CompletableFuture<Void> prepare(String user, int amount,
      ByteBuffer oldHash, ByteBuffer newHash) {
    if (amount <= 0 || newHash.remaining() != 32)
      return CompletableFuture.failedFuture(new IllegalArgumentException("invalid token preparation"));
    if (tokens.putIfAbsent(key(user, newHash), amount) != null)
      return CompletableFuture.failedFuture(new IllegalArgumentException("duplicate token"));
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Integer> validate(String user, ByteBuffer tokenHash) {
    Integer amount = tokens.remove(key(user, tokenHash));
    if (amount == null)
      return CompletableFuture.failedFuture(new IllegalArgumentException("token missing or used"));
    return CompletableFuture.completedFuture(amount);
  }
}
