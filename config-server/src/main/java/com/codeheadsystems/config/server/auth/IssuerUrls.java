package com.codeheadsystems.config.server.auth;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which URLs the server will fetch an issuer's discovery document and keys from: absolute
 * {@code https} URLs, or {@code http} on the loopback interface (local development and tests).
 * Anything else would let whoever is on the network path serve their own keys and mint tokens.
 */
public final class IssuerUrls {

  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  private IssuerUrls() {
  }

  /**
   * Whether a URL is safe to fetch keys from.
   *
   * @param url the URL, or null.
   * @return true for an absolute https URL with a host, or http on the loopback interface.
   */
  public static boolean isSecure(final URI url) {
    if (url == null || !url.isAbsolute() || url.getHost() == null || url.getRawFragment() != null) {
      return false;
    }
    final String scheme = url.getScheme().toLowerCase(Locale.ROOT);
    return scheme.equals("https")
        || scheme.equals("http") && LOOPBACK.contains(url.getHost().toLowerCase(Locale.ROOT));
  }
}
