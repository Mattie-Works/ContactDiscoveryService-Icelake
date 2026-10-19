/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;

/** Development/single-node alternative to the legacy Redis Cluster client. */
@Factory
@Requires(property = "redis-leaky-bucket.mode", value = "standalone")
@Requires(property = "redis-leaky-bucket.host")
public class StandaloneRedisLeakyBucketClientFactory {
  static final String CONNECTION_NAME = "standaloneRedisLeakyBucketConnection";

  @Bean(preDestroy = "shutdown")
  @Singleton
  @Named("standaloneRedisLeakyBucketClient")
  RedisClient client(
      @Value("${redis-leaky-bucket.host}") String host,
      @Value("${redis-leaky-bucket.port:6379}") int port,
      @Value("${redis-leaky-bucket.password:}") String password) {
    if (host.isBlank() || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid standalone Redis connection");
    }
    RedisURI.Builder builder = RedisURI.builder().withHost(host).withPort(port);
    if (password != null && !password.isBlank()) builder.withPassword(password.toCharArray());
    RedisClient client = RedisClient.create(builder.build());
    client.setOptions(ClientOptions.builder()
        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        .timeoutOptions(TimeoutOptions.builder().timeoutCommands(true)
            .fixedTimeout(Duration.ofSeconds(2)).build())
        .build());
    return client;
  }

  @Singleton
  @Bean(preDestroy = "close")
  @Named(CONNECTION_NAME)
  StatefulRedisConnection<String, String> connection(
      @Named("standaloneRedisLeakyBucketClient") RedisClient client) {
    return client.connect();
  }
}
