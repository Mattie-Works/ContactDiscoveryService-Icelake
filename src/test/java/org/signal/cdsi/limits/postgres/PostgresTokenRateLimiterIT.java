/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.signal.cdsi.limits.RateLimitExceededException;
import org.signal.cdsi.limits.TokenRateLimitConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL integration tests. Run with -Dtest=PostgresTokenRateLimiterIT. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTokenRateLimiterIT {
  @Container
  private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine");
  private static final ExecutorService IO = Executors.newFixedThreadPool(6);
  private static PGSimpleDataSource dataSource;

  @BeforeAll
  static void prepareDatabase() throws Exception {
    dataSource = new PGSimpleDataSource();
    dataSource.setURL(DATABASE.getJdbcUrl());
    dataSource.setUser(DATABASE.getUsername());
    dataSource.setPassword(DATABASE.getPassword());
    try (InputStream resource = PostgresTokenRateLimiterIT.class
        .getResourceAsStream("/db/postgres-token-rate-limit.sql")) {
      if (resource == null) throw new IllegalStateException("PostgreSQL schema resource missing");
      String ddl = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
      try (Connection connection = dataSource.getConnection();
           Statement statement = connection.createStatement()) {
        statement.execute(ddl);
      }
    }
  }

  @BeforeEach
  void clearDatabase() throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE TABLE cdsi_token_cost, cdsi_token_bucket CASCADE");
    }
  }

  @AfterAll
  static void shutdown() {
    IO.shutdownNow();
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T10:00:00Z"));
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
    void advance(Duration delta) { now.updateAndGet(current -> current.plus(delta)); }
  }

  private static PostgresTokenRateLimiter newLimiter(Clock clock, int capacity) {
    TokenRateLimitConfiguration limits = new TokenRateLimitConfiguration();
    limits.setBucketSize(capacity);
    limits.setLeakRateScalar(1);
    limits.setLeakRateDuration(Duration.ofHours(1));
    return new PostgresTokenRateLimiter(dataSource, clock, limits, IO, Duration.ofMinutes(5));
  }

  private static byte[] token(int marker) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) marker);
    return bytes;
  }

  private static void prepare(PostgresTokenRateLimiter limiter, String user, int amount, byte[] token) {
    limiter.prepare(user, amount, ByteBuffer.allocate(0), ByteBuffer.wrap(token)).join();
  }

  @Test
  void chargesOnceAndRejectsConsumedOrUnknownToken() {
    PostgresTokenRateLimiter limiter = newLimiter(Clock.systemUTC(), 3);
    byte[] first = token(1);
    prepare(limiter, "alice", 2, first);
    assertEquals(2, limiter.validate("alice", ByteBuffer.wrap(first)).join().intValue());

    assertThrows(CompletionException.class,
        () -> limiter.validate("alice", ByteBuffer.wrap(first)).join());
    assertThrows(CompletionException.class,
        () -> limiter.validate("alice", ByteBuffer.wrap(token(2))).join());

    // The first redemption consumed two permits; a second cost of two cannot fit.
    CompletionException failure = assertThrows(CompletionException.class,
        () -> prepare(limiter, "alice", 2, token(3)));
    assertInstanceOf(RateLimitExceededException.class, failure.getCause());
  }

  @Test
  void supportsOldTokenCostCarryForward() {
    PostgresTokenRateLimiter limiter = newLimiter(Clock.systemUTC(), 5);
    byte[] oldHash = token(1);
    byte[] newHash = token(2);
    prepare(limiter, "alice", 2, oldHash);
    limiter.prepare("alice", 1, ByteBuffer.wrap(oldHash), ByteBuffer.wrap(newHash)).join();
    assertEquals(3, limiter.validate("alice", ByteBuffer.wrap(newHash)).join().intValue());
  }

  @Test
  void isolatesUsersAndExpiresUnusedTokens() {
    MutableClock clock = new MutableClock();
    PostgresTokenRateLimiter limiter = newLimiter(clock, 10);
    byte[] hash = token(1);
    prepare(limiter, "alice", 1, hash);
    assertThrows(CompletionException.class,
        () -> limiter.validate("bob", ByteBuffer.wrap(hash)).join());
    clock.advance(Duration.ofMinutes(6));
    assertThrows(CompletionException.class,
        () -> limiter.validate("alice", ByteBuffer.wrap(hash)).join());
  }

  @Test
  void concurrentRedeemsCannotOverdrawBucket() {
    PostgresTokenRateLimiter limiter = newLimiter(Clock.systemUTC(), 3);
    byte[] a = token(1);
    byte[] b = token(2);
    prepare(limiter, "alice", 2, a);
    prepare(limiter, "alice", 2, b);

    CompletableFuture<Boolean> first = limiter.validate("alice", ByteBuffer.wrap(a))
        .handle((result, error) -> error == null);
    CompletableFuture<Boolean> second = limiter.validate("alice", ByteBuffer.wrap(b))
        .handle((result, error) -> error == null);
    assertEquals(1, (first.join() ? 1 : 0) + (second.join() ? 1 : 0));
  }

  @Test
  void overCapacityRedemptionCanSucceedAfterBucketLeaks() {
    MutableClock clock = new MutableClock();
    PostgresTokenRateLimiter limiter = newLimiter(clock, 3);
    byte[] first = token(1);
    byte[] second = token(2);
    prepare(limiter, "alice", 2, first);
    prepare(limiter, "alice", 2, second);
    assertEquals(2, limiter.validate("alice", ByteBuffer.wrap(first)).join().intValue());
    CompletionException blocked = assertThrows(CompletionException.class,
        () -> limiter.validate("alice", ByteBuffer.wrap(second)).join());
    assertInstanceOf(RateLimitExceededException.class, blocked.getCause());
    clock.advance(Duration.ofHours(1));
    // The failed transaction did not consume the second token.
    assertEquals(2, limiter.validate("alice", ByteBuffer.wrap(second)).join().intValue());
  }
}
