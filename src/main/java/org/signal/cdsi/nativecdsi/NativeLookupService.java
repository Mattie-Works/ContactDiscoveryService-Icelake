/* SPDX-License-Identifier: AGPL-3.0-only */
package org.signal.cdsi.nativecdsi;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.signal.cdsi.enclave.DirectoryEntry;
import org.signal.cdsi.limits.TokenRateLimiter;

/**
 * Explicitly non-attested lookup service for a custom client.
 * Caller MUST authenticate the user and enforce connection limits before invoking.
 * This service uses the existing token limiter's prepare/validate handshake and
 * does not expose an unauthenticated direct lookup endpoint.
 */
public final class NativeLookupService {
  private static final int TOKEN_BYTES = 32;
  private final NativeDirectoryIndex directory;
  private final TokenRateLimiter limiter;
  private final SecureRandom random = new SecureRandom();
  private final int maxNumbers;

  public NativeLookupService(NativeDirectoryIndex directory, TokenRateLimiter limiter, int maxNumbers) {
    this.directory = Objects.requireNonNull(directory);
    this.limiter = Objects.requireNonNull(limiter);
    if (maxNumbers < 1) throw new IllegalArgumentException("maxNumbers must be positive");
    this.maxNumbers = maxNumbers;
  }

  /** A single-use token must be delivered to the client before redeeming it. */
  public CompletableFuture<byte[]> prepare(String authenticatedUser, int count) {
    requireUser(authenticatedUser);
    if (count < 1 || count > maxNumbers) throw new IllegalArgumentException("invalid query size");
    byte[] token = new byte[TOKEN_BYTES];
    random.nextBytes(token);
    return limiter.prepare(authenticatedUser, count, ByteBuffer.wrap(new byte[TOKEN_BYTES]),
        ByteBuffer.wrap(token.clone())).thenApply(ignored -> token.clone());
  }

  /**
   * Redeem the token and perform lookups only after rate-limit validation.
   * The returned list is positionally aligned to the requested E.164 values;
   * absent entries are represented by null.
   */
  public CompletableFuture<List<DirectoryEntry>> lookup(String authenticatedUser, byte[] token, List<Long> numbers) {
    requireUser(authenticatedUser);
    Objects.requireNonNull(numbers);
    if (numbers.isEmpty() || numbers.size() > maxNumbers || numbers.contains(null))
      throw new IllegalArgumentException("invalid query size");
    if (token == null || token.length != TOKEN_BYTES) throw new IllegalArgumentException("invalid token");
    return limiter.validate(authenticatedUser, ByteBuffer.wrap(token.clone()))
        .thenApply(permitsUsed -> {
          // Fail closed if the limiter returns an unexpected result.
          if (permitsUsed == null || permitsUsed < numbers.size())
            throw new IllegalStateException("insufficient validated permits");
          List<DirectoryEntry> result = new ArrayList<>(numbers.size());
          for (long number : numbers) result.add(directory.find(number).orElse(null));
          return result;
        });
  }

  private static void requireUser(String user) {
    if (user == null || user.isBlank()) throw new IllegalArgumentException("authenticated user required");
  }
}
