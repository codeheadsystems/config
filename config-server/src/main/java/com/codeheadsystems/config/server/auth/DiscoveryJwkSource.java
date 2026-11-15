package com.codeheadsystems.config.server.auth;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An issuer's signing keys, found through OpenID Connect Discovery on first use: fetches
 * {@code <issuer>/.well-known/openid-configuration}, checks its {@code issuer} and that its
 * {@code jwks_uri} is secure ({@link IssuerUrls}), then serves keys from that JWKS.
 *
 * <p>Nimbus caches the JWKS (for the key cache TTL), fetches it again when a token names an unknown
 * {@code kid} (at most twice per refresh interval), retries a failed fetch once, and, while the
 * issuer cannot be reached, keeps serving the last keys it fetched for the outage tolerance.
 *
 * <p>A failed discovery is retried on a later request, at most every {@value #RETRY_SECONDS}
 * seconds, so an issuer outage does not turn every request into a discovery attempt.
 */
final class DiscoveryJwkSource implements JWKSource<SecurityContext>, Closeable {

  static final long RETRY_SECONDS = 5;

  private static final JWKSelector ANY_KEY = new JWKSelector(new JWKMatcher.Builder().build());

  private final String issuer;
  private final ResourceRetriever retriever;
  private final long refreshIntervalMillis;
  private final long cacheTtlMillis;
  private final long outageToleranceMillis;
  private volatile JWKSource<SecurityContext> keys;
  private long lastFailure;
  private String lastFailureMessage;
  private boolean failed;

  DiscoveryJwkSource(final String issuer, final ResourceRetriever retriever, final long refreshIntervalMillis,
                     final long cacheTtlMillis, final long outageToleranceMillis) {
    this.issuer = issuer;
    this.retriever = retriever;
    this.refreshIntervalMillis = refreshIntervalMillis;
    this.cacheTtlMillis = cacheTtlMillis;
    this.outageToleranceMillis = outageToleranceMillis;
  }

  @Override
  public List<JWK> get(final JWKSelector selector, final SecurityContext context) throws KeySourceException {
    return keys().get(selector, context);
  }

  /**
   * Checks the issuer is usable: discovery works and the JWKS has at least one key.
   *
   * @throws KeySourceException if not.
   */
  void check() throws KeySourceException {
    if (keys().get(ANY_KEY, null).isEmpty()) {
      throw new KeySourceException("The JWKS of " + issuer + " has no keys");
    }
  }

  @Override
  public void close() throws IOException {
    if (keys instanceof Closeable closeable) {
      closeable.close();
    }
  }

  private JWKSource<SecurityContext> keys() throws KeySourceException {
    final JWKSource<SecurityContext> found = keys;
    if (found != null) {
      return found;
    }
    synchronized (this) {
      if (keys == null) {
        if (failed && System.nanoTime() - lastFailure < TimeUnit.SECONDS.toNanos(RETRY_SECONDS)) {
          throw new KeySourceException(lastFailureMessage + " (failed recently; not retrying yet)");
        }
        try {
          final JWKSourceBuilder<SecurityContext> builder = JWKSourceBuilder.<SecurityContext>create(discover(), retriever)
              .cache(cacheTtlMillis, JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
              .rateLimited(refreshIntervalMillis)
              .retrying(true);
          keys = (outageToleranceMillis > 0
              ? builder.outageTolerant(outageToleranceMillis)
              : builder.outageTolerant(false)).build();
          failed = false;
        } catch (final KeySourceException e) {
          failed = true;
          lastFailure = System.nanoTime();
          lastFailureMessage = e.getMessage();
          throw e;
        }
      }
      return keys;
    }
  }

  /** Finds the JWKS URL from the discovery document. */
  private URL discover() throws KeySourceException {
    final String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    try {
      final URL discovery = URI.create(base + "/.well-known/openid-configuration").toURL();
      final Map<String, Object> document = JSONObjectUtils.parse(retriever.retrieveResource(discovery).getContent());
      final String named = JSONObjectUtils.getString(document, "issuer");
      if (!issuer.equals(named)) {
        throw new KeySourceException("Discovery for " + issuer + " names a different issuer: " + named);
      }
      final URI jwks = JSONObjectUtils.getURI(document, "jwks_uri");
      if (!IssuerUrls.isSecure(jwks)) {
        throw new KeySourceException("Discovery for " + issuer + " has a missing or insecure jwks_uri: " + jwks);
      }
      return jwks.toURL();
    } catch (final IOException | ParseException | IllegalArgumentException e) {
      throw new KeySourceException("Discovery for " + issuer + " failed: " + e.getMessage(), e);
    }
  }
}
