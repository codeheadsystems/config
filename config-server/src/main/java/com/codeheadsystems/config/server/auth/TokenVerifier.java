package com.codeheadsystems.config.server.auth;

import com.codeheadsystems.config.server.AuthConfig;
import com.codeheadsystems.config.server.IssuerConfig;
import com.codeheadsystems.config.server.api.ApiException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks bearer tokens against the configured issuers. The token's {@code iss} picks the issuer;
 * that issuer then checks the signature and the claims. See the {@code bearerAuth} security
 * scheme in {@code openapi/config-api.yaml} for the rules.
 */
public final class TokenVerifier implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TokenVerifier.class);
  private static final int MAX_DOCUMENT_BYTES = 512 * 1024;

  private final Map<String, TrustedIssuer> issuers;
  private final Map<String, DiscoveryJwkSource> keysByName;

  /**
   * Creates a verifier for the configured issuers. Nothing is fetched until the first token or
   * health check.
   *
   * @param config            the issuers and their settings.
   * @param retryAfterSeconds {@code Retry-After} when an issuer cannot be reached.
   * @throws IllegalArgumentException if two issuers share a name or an issuer URL.
   */
  public TokenVerifier(final AuthConfig config, final long retryAfterSeconds) {
    final int timeoutMillis = Math.toIntExact(config.getTimeout().toMilliseconds());
    final ResourceRetriever retriever =
        new DefaultResourceRetriever(timeoutMillis, timeoutMillis, MAX_DOCUMENT_BYTES);
    final int clockSkew = Math.toIntExact(config.getClockSkew().toSeconds());
    final long refreshMillis = config.getKeyRefreshInterval().toMilliseconds();
    final long refreshSeconds = Math.max(1, (refreshMillis + 999) / 1000);
    final long outageMillis = config.getKeyOutageTolerance().toMilliseconds();
    final long cacheMillis = config.getKeyCacheTtl().toMilliseconds();
    final Map<String, TrustedIssuer> byIssuer = new HashMap<>();
    final Map<String, DiscoveryJwkSource> byName = new LinkedHashMap<>();
    final Set<String> names = new HashSet<>();
    for (final IssuerConfig issuer : config.getIssuers()) {
      final String iss = issuer.getIssuer().toString();
      if (!names.add(issuer.getName()) || byIssuer.containsKey(iss)) {
        throw new IllegalArgumentException("Issuer names and URLs must be unique: " + issuer.getName() + ", " + iss);
      }
      final DiscoveryJwkSource keys = new DiscoveryJwkSource(iss, retriever, refreshMillis, cacheMillis, outageMillis);
      byName.put(issuer.getName(), keys);
      byIssuer.put(iss, new TrustedIssuer(issuer.getName(), iss, issuer.getAudience(), keys, clockSkew,
          retryAfterSeconds, refreshSeconds));
    }
    if (byIssuer.isEmpty()) {
      LOG.warn("No trusted token issuers are configured (auth.issuers): every request will be refused");
    }
    this.issuers = Map.copyOf(byIssuer);
    this.keysByName = byName;
  }

  /**
   * Verifies a token.
   *
   * @param token the compact JWT from the {@code Authorization} header.
   * @return who sent it.
   * @throws ApiException {@code 401} if the token is not a valid signed JWT or its issuer is not
   *                      trusted, {@code 503} if the issuer cannot be reached.
   */
  public Caller verify(final String token) {
    final JWT parsed;
    try {
      parsed = JWTParser.parse(token);
    } catch (final ParseException e) {
      throw ApiException.invalidToken("The access token is not a valid JWT");
    }
    // Only signed JWTs: an unsigned (alg none) or encrypted JWT is refused before its claims are
    // read (an encrypted JWT's claims are not readable without decrypting it).
    if (!(parsed instanceof SignedJWT signed)) {
      throw ApiException.invalidToken("The access token is not a signed JWT");
    }
    final String iss;
    try {
      final JWTClaimsSet claims = signed.getJWTClaimsSet();
      iss = claims.getIssuer();
    } catch (final ParseException e) {
      throw ApiException.invalidToken("The access token is not a valid JWT");
    }
    final TrustedIssuer issuer = iss == null ? null : issuers.get(iss);
    if (issuer == null) {
      throw ApiException.invalidToken("The access token's issuer is not trusted");
    }
    return issuer.verify(signed);
  }

  /**
   * The configured issuers' names, for health checks.
   *
   * @return the names, in configuration order.
   */
  public Set<String> issuerNames() {
    return keysByName.keySet();
  }

  /**
   * Checks an issuer is usable: discovery works and its JWKS has keys.
   *
   * @param name the issuer's configured name.
   * @throws KeySourceException if it is not.
   */
  public void check(final String name) throws KeySourceException {
    final DiscoveryJwkSource keys = keysByName.get(name);
    if (keys == null) {
      throw new IllegalArgumentException("No issuer named " + name);
    }
    keys.check();
  }

  /** Stops the key sources' background refresh threads. */
  @Override
  public void close() {
    for (final DiscoveryJwkSource keys : keysByName.values()) {
      try {
        keys.close();
      } catch (final IOException e) {
        LOG.warn("Could not close the key source", e);
      }
    }
  }
}
