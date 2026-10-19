/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.cdsi.limits.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Dedicated pool for transactional, self-hosted token quotas.
 * Only created when cdsi.token-limiter.backend=postgres.
 */
@Factory
@Requires(property = "cdsi.token-limiter.backend", value = "postgres")
public class PostgresTokenRateLimitDataSourceFactory {
  public static final String DATA_SOURCE_NAME = "cdsiTokenRateLimitDataSource";

  @Bean(preDestroy = "close")
  @Singleton
  @Named(DATA_SOURCE_NAME)
  public HikariDataSource create(
      @Value("${postgres-token-rate-limit.jdbc-url}") String jdbcUrl,
      @Value("${postgres-token-rate-limit.username}") String username,
      @Value("${postgres-token-rate-limit.password}") String password,
      @Value("${postgres-token-rate-limit.pool-size:10}") int poolSize) {
    if (poolSize < 1 || poolSize > 100) {
      throw new IllegalArgumentException("PostgreSQL pool size must be between 1 and 100");
    }
    HikariConfig config = new HikariConfig();
    config.setPoolName("cdsi-token-quotas");
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(5_000);
    config.setValidationTimeout(2_000);
    config.setAutoCommit(false);
    return new HikariDataSource(config);
  }
}
