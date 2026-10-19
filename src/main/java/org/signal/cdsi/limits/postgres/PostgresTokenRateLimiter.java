/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.postgres;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import org.signal.cdsi.limits.RateLimitExceededException;
import org.signal.cdsi.limits.RateLimits;
import org.signal.cdsi.limits.TokenRateLimitConfiguration;
import org.signal.cdsi.limits.TokenRateLimiter;

/**
 * Transactional replacement for CosmosTokenRateLimiter on native Linux.
 *
 * Prepare checks a user's bucket without spending credits; validate redeems
 * precisely one previously prepared token and charges its stored cost.
 * The bucket's SELECT FOR UPDATE lock serializes operations across JVMs.
 * Unknown/expired/already used tokens are rejected (not treated as free).
 */
@Singleton
@Requires(property = "cdsi.token-limiter.backend", value = "postgres")
public final class PostgresTokenRateLimiter implements TokenRateLimiter {
  private static final int HASH_LENGTH = 32;

  private final DataSource dataSource;
  private final Clock clock;
  private final TokenRateLimitConfiguration limits;
  private final ExecutorService executor;
  private final Duration tokenTtl;

  public PostgresTokenRateLimiter(
      @Named(PostgresTokenRateLimitDataSourceFactory.DATA_SOURCE_NAME) DataSource dataSource,
      Clock clock,
      TokenRateLimitConfiguration limits,
      @Named(TaskExecutors.IO) ExecutorService executor,
      @Value("${postgres-token-rate-limit.token-ttl:PT5M}") Duration tokenTtl) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.clock = Objects.requireNonNull(clock);
    this.limits = Objects.requireNonNull(limits);
    this.executor = Objects.requireNonNull(executor);
    this.tokenTtl = Objects.requireNonNull(tokenTtl);
    if (tokenTtl.isZero() || tokenTtl.isNegative()
        || limits.getBucketSize() <= 0 || limits.getLeakRateScalar() <= 0
        || limits.getLeakRateDuration() == null || limits.getLeakRateDuration().isZero()
        || limits.getLeakRateDuration().isNegative()
        || limits.getLeakRateDuration().toMillis() == 0) {
      throw new IllegalArgumentException("PostgreSQL token quota and TTL settings must be positive");
    }
  }

  @Override
  public CompletableFuture<Void> prepare(String user, int amount, ByteBuffer oldTokenHash, ByteBuffer newTokenHash) {
    requireUser(user);
    if (amount < 0) throw new IllegalArgumentException("negative token cost");
    byte[] oldHash = copyHash(oldTokenHash, true);
    byte[] newHash = copyHash(newTokenHash, false);
    if (oldHash.length > 0 && java.util.Arrays.equals(oldHash, newHash))
      throw new IllegalArgumentException("new token must differ from old token");

    return CompletableFuture.supplyAsync(() -> transaction((connection, now) -> {
      Bucket bucket = lockBucket(connection, user, now, true);
      // Clean a user's expired pending entries while holding their bucket lock.
      try (PreparedStatement cleanup = connection.prepareStatement(
          "DELETE FROM cdsi_token_cost WHERE user_key = ? AND expires_at <= ?")) {
        cleanup.setString(1, user);
        cleanup.setTimestamp(2, Timestamp.from(now));
        cleanup.executeUpdate();
      }
      long previousCost = oldHash.length == 0 ? 0 : getTokenCost(connection, user, oldHash, now);
      long newCost = Math.addExact(previousCost, amount);
      if (newCost <= 0 || newCost > limits.getBucketSize() || newCost > Integer.MAX_VALUE)
        throw new IllegalArgumentException("requested token cost exceeds bucket capacity");

      // Prepare must not spend any permits. It must, however, detect requests
      // that could not be satisfied even if redeemed immediately.
      RateLimits.calculateBucketUtilization(limits, bucket.updatedAt(), now, bucket.amount(), newCost);
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO cdsi_token_cost(user_key,token_hash,cost,expires_at) VALUES (?,?,?,?)")) {
        insert.setString(1, user);
        insert.setBytes(2, newHash);
        insert.setInt(3, Math.toIntExact(newCost));
        insert.setTimestamp(4, Timestamp.from(now.plus(tokenTtl)));
        insert.executeUpdate();
      }
      return null;
    }), executor);
  }

  @Override
  public CompletableFuture<Integer> validate(String user, ByteBuffer tokenHash) {
    requireUser(user);
    byte[] hash = copyHash(tokenHash, false);
    return CompletableFuture.supplyAsync(() -> transaction((connection, now) -> {
      Bucket bucket = lockBucket(connection, user, now, false);
      if (bucket == null) throw new IllegalArgumentException("unknown token");

      int cost = getTokenCost(connection, user, hash, now);
      if (cost <= 0) throw new IllegalArgumentException("unknown, expired or consumed token");

      // This calculation may throw RateLimitExceededException. The transaction
      // rolls back and neither the bucket nor token is modified.
      double remaining = RateLimits.calculateBucketUtilization(
          limits, bucket.updatedAt(), now, bucket.amount(), cost);
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE cdsi_token_bucket SET amount = ?, updated_at = ? WHERE user_key = ?")) {
        update.setDouble(1, remaining);
        update.setTimestamp(2, Timestamp.from(now));
        update.setString(3, user);
        if (update.executeUpdate() != 1) throw new SQLException("bucket update failed");
      }
      try (PreparedStatement consume = connection.prepareStatement(
          "DELETE FROM cdsi_token_cost WHERE user_key = ? AND token_hash = ?")) {
        consume.setString(1, user);
        consume.setBytes(2, hash);
        if (consume.executeUpdate() != 1) throw new SQLException("token consumption conflict");
      }
      return cost;
    }), executor);
  }

  /** Reap stale unused tokens, including users who never make another request. */
  @Scheduled(initialDelay = "1h", fixedDelay = "1h")
  public void pruneExpiredTokens() {
    CompletableFuture.runAsync(() -> transaction((connection, now) -> {
      try (PreparedStatement cleanup = connection.prepareStatement(
          "DELETE FROM cdsi_token_cost WHERE expires_at <= ?")) {
        cleanup.setTimestamp(1, Timestamp.from(now));
        cleanup.executeUpdate();
      }
      return null;
    }), executor).exceptionally(error -> {
      org.slf4j.LoggerFactory.getLogger(PostgresTokenRateLimiter.class)
          .warn("PostgreSQL token expiration cleanup failed", error);
      return null;
    });
  }

  private record Bucket(double amount, Instant updatedAt) {}

  private Bucket lockBucket(Connection connection, String user, Instant now, boolean create) throws SQLException {
    if (create) {
      try (PreparedStatement createBucket = connection.prepareStatement(
          "INSERT INTO cdsi_token_bucket(user_key,amount,updated_at) VALUES (?,0,?) ON CONFLICT DO NOTHING")) {
        createBucket.setString(1, user);
        createBucket.setTimestamp(2, Timestamp.from(now));
        createBucket.executeUpdate();
      }
    }
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT amount,updated_at FROM cdsi_token_bucket WHERE user_key = ? FOR UPDATE")) {
      select.setString(1, user);
      try (ResultSet rows = select.executeQuery()) {
        if (!rows.next()) return null;
        return new Bucket(rows.getDouble(1), rows.getTimestamp(2).toInstant());
      }
    }
  }

  private int getTokenCost(Connection connection, String user, byte[] hash, Instant now) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT cost FROM cdsi_token_cost WHERE user_key = ? AND token_hash = ? AND expires_at > ?")) {
      query.setString(1, user);
      query.setBytes(2, hash);
      query.setTimestamp(3, Timestamp.from(now));
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  private static byte[] copyHash(ByteBuffer token, boolean allowEmpty) {
    Objects.requireNonNull(token, "token hash required");
    ByteBuffer copy = token.duplicate();
    if ((copy.remaining() != HASH_LENGTH) && !(allowEmpty && !copy.hasRemaining()))
      throw new IllegalArgumentException("token hashes must be 32 bytes");
    byte[] result = new byte[copy.remaining()];
    copy.get(result);
    return result;
  }

  private static void requireUser(String user) {
    if (user == null || user.isBlank() || user.length() > 512)
      throw new IllegalArgumentException("invalid token quota user");
  }

  private interface Work<T> {
    T apply(Connection connection, Instant now) throws SQLException, RateLimitExceededException;
  }

  private <T> T transaction(Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(connection, clock.instant());
        connection.commit();
        return result;
      } catch (Exception | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        if (failure instanceof Error err) throw err;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new CompletionException(failure);
      }
    } catch (SQLException failure) {
      throw new CompletionException(failure);
    }
  }
}
