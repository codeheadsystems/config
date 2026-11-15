package com.codeheadsystems.config.server.settings;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.CacheConfig;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code Cache-Control} header for an application's settings (RFC 9111, RFC 5861):
 * {@code private, max-age=N, stale-while-revalidate=N, stale-if-error=M}. {@code N} and {@code M}
 * come from METADATA ({@code cache.max-age}, {@code cache.stale-if-error}, in seconds), or the
 * server's defaults when they are not set or not valid. Values are capped at
 * {@value #MAX_SECONDS} (2^31 - 1), so clients can read them as 32-bit integers (RFC 9111,
 * section 1.2.2).
 */
final class CachePolicy {

  static final Key MAX_AGE = Key.from("cache.max-age");
  static final Key STALE_IF_ERROR = Key.from("cache.stale-if-error");
  static final long MAX_SECONDS = Integer.MAX_VALUE;

  private static final Logger LOG = LoggerFactory.getLogger(CachePolicy.class);
  private static final int MAX_WARNINGS_REMEMBERED = 10_000;
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  private CachePolicy() {
  }

  /**
   * Builds the header value.
   *
   * @param metadata the METADATA set's values; empty if there is no set.
   * @param defaults the server's defaults.
   * @param where    which set the METADATA belongs to, for the log.
   * @return the {@code Cache-Control} value.
   */
  static String cacheControl(final Map<Key, Value> metadata, final CacheConfig defaults, final String where) {
    final long maxAge = seconds(metadata, MAX_AGE, defaults.getDefaultMaxAge(), where);
    final long staleIfError = seconds(metadata, STALE_IF_ERROR, defaults.getDefaultStaleIfError(), where);
    return "private, max-age=" + maxAge + ", stale-while-revalidate=" + maxAge + ", stale-if-error=" + staleIfError;
  }

  private static long seconds(final Map<Key, Value> metadata, final Key key, final long fallback, final String where) {
    return Optional.ofNullable(metadata.get(key)).map(value -> {
      try {
        final long seconds = Long.parseLong(value.value().trim());
        if (seconds >= 0) {
          return Math.min(seconds, MAX_SECONDS);
        }
      } catch (final NumberFormatException e) {
        // fall through to the warning
      }
      warnOnce(where, key, value);
      return Math.min(fallback, MAX_SECONDS);
    }).orElse(Math.min(fallback, MAX_SECONDS));
  }

  /** Warns once per set, key and value, rather than on every request. */
  private static void warnOnce(final String where, final Key key, final Value value) {
    if (WARNED.size() >= MAX_WARNINGS_REMEMBERED) {
      WARNED.clear();
    }
    if (WARNED.add(where + '\n' + key.name() + '\n' + value.value())) {
      LOG.warn("Ignoring METADATA {} = '{}' for {}: not a whole number of seconds; using the default",
          key.name(), value.value(), where);
    }
  }
}
