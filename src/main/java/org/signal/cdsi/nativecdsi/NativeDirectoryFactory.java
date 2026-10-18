/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.signal.cdsi.limits.TokenRateLimiter;

/**
 * Opt-in native backend components.
 *
 * The directory must be populated by the account synchronization adapter
 * before serving discovery queries. Enabling this factory alone does not
 * disable the existing SGX backend.
 */
@Factory
@Requires(property = "cdsi.native.enabled", value = "true")
public class NativeDirectoryFactory {
  @Singleton
  NativeDirectoryIndex index() {
    return new NativeDirectoryIndex();
  }

  @Singleton
  NativeLookupService lookupService(NativeDirectoryIndex index, TokenRateLimiter limiter,
      @Value("${cdsi.native.max-numbers:1024}") int maxNumbers) {
    return new NativeLookupService(index, limiter, maxNumbers);
  }
}
