/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.redis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.signal.cdsi.limits.LeakyBucketRateLimitConfiguration;
import org.signal.cdsi.limits.LeakyBucketRateLimiter;
import org.signal.cdsi.limits.RateLimitExceededException;

/**
 * Standalone Redis connection limiter using the existing atomic Lua script.
 * Unlike the legacy cluster implementation, backend failures are fail-closed.
 */
@EachBean(LeakyBucketRateLimitConfiguration.class)
@Requires(property = "redis-leaky-bucket.mode", value = "standalone")
@Requires(bean = StatefulRedisConnection.class)
public final class StandaloneRedisLeakyBucketRateLimiter implements LeakyBucketRateLimiter {
  private final StatefulRedisConnection<String, String> connection;
  private final LeakyBucketRateLimitConfiguration config;
  private final Clock clock;
  private final String script;
  private final String sha;

  public StandaloneRedisLeakyBucketRateLimiter(
      @Named(StandaloneRedisLeakyBucketClientFactory.CONNECTION_NAME)
          StatefulRedisConnection<String, String> connection,
      LeakyBucketRateLimitConfiguration config, Clock clock) throws IOException {
    this.connection = connection;
    this.config = config;
    this.clock = clock;
    try (InputStream input = getClass().getResourceAsStream("validate_rate_limit.lua")) {
      if (input == null) throw new IOException("Missing Redis limiter script");
      script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    try {
      sha = java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public CompletableFuture<Void> validate(String user, int amount) {
    if (user == null || user.isBlank() || amount < 1) {
      throw new IllegalArgumentException("Invalid connection limit key or cost");
    }
    double leakPerMs = (double) config.getLeakRateScalar() / config.getLeakRateDuration().toMillis();
    if (config.getBucketSize() <= 0 || leakPerMs <= 0) {
      return CompletableFuture.failedFuture(new IllegalStateException("Invalid Redis limiter settings"));
    }
    String[] keys = {"leaky_bucket::" + config.getName() + "::" + user};
    String[] args = {
        Integer.toString(config.getBucketSize()),
        Double.toString(leakPerMs),
        Long.toString(clock.instant().toEpochMilli()),
        Integer.toString(amount)
    };
    return connection.async().<Long>evalsha(sha, ScriptOutputType.INTEGER, keys, args)
        .toCompletableFuture()
        .exceptionallyCompose(failure -> {
          Throwable cause = failure instanceof CompletionException && failure.getCause() != null
              ? failure.getCause() : failure;
          if (cause instanceof RedisNoScriptException) {
            return connection.async().<Long>eval(script, ScriptOutputType.INTEGER, keys, args)
                .toCompletableFuture();
          }
          return CompletableFuture.failedFuture(cause);
        })
        .thenCompose(overflow -> {
          if (overflow == null || overflow < 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Invalid Redis limiter response"));
          }
          if (overflow == 0L) return CompletableFuture.<Void>completedFuture(null);
          long retryMs = (long) Math.ceil(overflow / leakPerMs);
          return CompletableFuture.failedFuture(
              new RateLimitExceededException(Duration.ofMillis(Math.max(1, retryMs))));
        });
  }
}
