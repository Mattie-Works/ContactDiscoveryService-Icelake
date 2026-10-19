/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.redis;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.signal.cdsi.limits.LeakyBucketRateLimitConfiguration;
import org.signal.cdsi.limits.RateLimitExceededException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the same Lua rate limiter against an actual standalone Redis. */
@Testcontainers(disabledWithoutDocker = true)
class StandaloneRedisLeakyBucketRateLimiterIT {
  @Container
  private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
      .withExposedPorts(6379);

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @BeforeAll
  static void connect() {
    client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    connection = client.connect();
  }

  @AfterAll
  static void disconnect() {
    if (connection != null) connection.close();
    if (client != null) client.shutdown();
  }

  @Test
  void appliesAtomicConnectionQuotaInStandaloneMode() throws Exception {
    LeakyBucketRateLimitConfiguration config = new LeakyBucketRateLimitConfiguration("connections");
    config.setBucketSize(1);
    config.setLeakRateScalar(1);
    config.setLeakRateDuration(Duration.ofMinutes(1));

    StandaloneRedisLeakyBucketRateLimiter limiter =
        new StandaloneRedisLeakyBucketRateLimiter(connection, config, Clock.systemUTC());

    limiter.validate("authenticated-user", 1).join();
    CompletionException overLimit = assertThrows(CompletionException.class,
        () -> limiter.validate("authenticated-user", 1).join());
    assertInstanceOf(RateLimitExceededException.class, overLimit.getCause());
  }
}
