package com.codeheadsystems.config.server.auth;

import com.codeheadsystems.config.server.RateLimitConfig;
import com.codeheadsystems.config.server.api.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Limits requests per bearer token with a token bucket ({@link RateLimitConfig}). Buckets are kept
 * by a hash of the token, never the token itself, and dropped after ten idle minutes.
 */
@Singleton
public final class RateLimiter {

  private static final long MAX_BUCKETS = 100_000;

  private final double capacity;
  private final double permitsPerNano;
  private final LongSupplier nanoTime;
  private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofMinutes(10))
      .maximumSize(MAX_BUCKETS)
      .build();

  /**
   * Creates the limiter.
   *
   * @param config the rate and burst.
   */
  @Inject
  public RateLimiter(final RateLimitConfig config) {
    this(config, System::nanoTime);
  }

  RateLimiter(final RateLimitConfig config, final LongSupplier nanoTime) {
    this.capacity = config.getBurst();
    this.permitsPerNano = config.getRequestsPerMinute() / (double) TimeUnit.MINUTES.toNanos(1);
    this.nanoTime = nanoTime;
  }

  /**
   * Takes one request from the token's bucket.
   *
   * @param token the bearer token.
   * @throws ApiException {@code 429} with {@code Retry-After} if the bucket is empty.
   */
  public void acquire(final String token) {
    final long now = nanoTime.getAsLong();
    final Bucket bucket = buckets.get(hash(token), key -> new Bucket(capacity, now));
    final long waitNanos = bucket.take(now, capacity, permitsPerNano);
    if (waitNanos > 0) {
      throw ApiException.tooManyRequests(Math.max(1, (waitNanos + TimeUnit.SECONDS.toNanos(1) - 1)
          / TimeUnit.SECONDS.toNanos(1)));
    }
  }

  private static String hash(final String token) {
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Permits left and when they were last counted. */
  private static final class Bucket {

    private double permits;
    private long updated;

    Bucket(final double permits, final long updated) {
      this.permits = permits;
      this.updated = updated;
    }

    /** Returns 0 if a permit was taken, otherwise the nanoseconds until one is available. */
    synchronized long take(final long now, final double capacity, final double permitsPerNano) {
      permits = Math.min(capacity, permits + Math.max(0, now - updated) * permitsPerNano);
      updated = now;
      if (permits >= 1) {
        permits -= 1;
        return 0;
      }
      return (long) Math.ceil((1 - permits) / permitsPerNano);
    }
  }
}
