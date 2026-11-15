package com.codeheadsystems.config.client;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The {@code Cache-Control} the server sends with settings (RFC 9111, RFC 5861):
 * {@code private, max-age=N, stale-while-revalidate=N, stale-if-error=M}. Missing or malformed
 * directives fall back to the defaults the spec gives.
 *
 * @param maxAge               how long values are fresh: the wait before the next check.
 * @param staleWhileRevalidate after {@code maxAge}, how long the values are served while a check
 *                             runs.
 * @param staleIfError         after {@code maxAge}, how long the values are served while checks
 *                             fail before they are reported as stale.
 */
record CachePolicy(Duration maxAge, Duration staleWhileRevalidate, Duration staleIfError) {

  static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(30);
  static final Duration DEFAULT_STALE_IF_ERROR = Duration.ofDays(1);
  static final CachePolicy DEFAULT = new CachePolicy(DEFAULT_MAX_AGE, DEFAULT_MAX_AGE, DEFAULT_STALE_IF_ERROR);

  /**
   * Parses the header.
   *
   * @param header the {@code Cache-Control} value, if any.
   * @return the policy.
   */
  static CachePolicy parse(final Optional<String> header) {
    if (header.isEmpty()) {
      return DEFAULT;
    }
    OptionalLong maxAge = OptionalLong.empty();
    OptionalLong staleWhileRevalidate = OptionalLong.empty();
    OptionalLong staleIfError = OptionalLong.empty();
    for (final String directive : header.get().split(",")) {
      final String[] parts = directive.trim().split("=", 2);
      if (parts.length != 2) {
        continue;
      }
      final OptionalLong seconds = seconds(parts[1]);
      switch (parts[0].trim().toLowerCase(Locale.ROOT)) {
        case "max-age" -> maxAge = seconds;
        case "stale-while-revalidate" -> staleWhileRevalidate = seconds;
        case "stale-if-error" -> staleIfError = seconds;
        default -> {
          // other directives (private) do not affect polling
        }
      }
    }
    final Duration fresh = maxAge.isPresent() ? Duration.ofSeconds(maxAge.getAsLong()) : DEFAULT_MAX_AGE;
    return new CachePolicy(fresh,
        staleWhileRevalidate.isPresent() ? Duration.ofSeconds(staleWhileRevalidate.getAsLong()) : fresh,
        staleIfError.isPresent() ? Duration.ofSeconds(staleIfError.getAsLong()) : DEFAULT_STALE_IF_ERROR);
  }

  private static OptionalLong seconds(final String value) {
    try {
      final long seconds = Long.parseLong(value.trim().replace("\"", ""));
      return seconds < 0 ? OptionalLong.empty() : OptionalLong.of(seconds);
    } catch (final NumberFormatException e) {
      return OptionalLong.empty();
    }
  }
}
