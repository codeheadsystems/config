package com.codeheadsystems.config.server.auth;

import com.codeheadsystems.config.server.api.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verifies tokens from one configured issuer. */
final class TrustedIssuer {

  /** Asymmetric algorithms only: no {@code none}, no HMAC (RFC 8725). The spec lists the same. */
  static final Set<JWSAlgorithm> ALGORITHMS = Set.of(
      JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
      JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
      JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

  private static final Logger LOG = LoggerFactory.getLogger(TrustedIssuer.class);

  private final String name;
  private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
  private final long retryAfterSeconds;
  private final long keyRefreshSeconds;

  TrustedIssuer(final String name, final String issuer, final String audience, final JWKSource<SecurityContext> keys,
                final int clockSkewSeconds, final long retryAfterSeconds, final long keyRefreshSeconds) {
    this.name = name;
    this.retryAfterSeconds = retryAfterSeconds;
    this.keyRefreshSeconds = keyRefreshSeconds;
    // JWT, RFC 9068's at+jwt, or no typ at all (Kubernetes service account tokens have none).
    processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
        JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
    final DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
        Set.of(audience),
        new JWTClaimsSet.Builder().issuer(issuer).build(),
        Set.of("iss", "sub", "aud", "exp"),
        null);
    claims.setMaxClockSkew(clockSkewSeconds);
    processor.setJWTClaimsSetVerifier(claims);
  }

  /**
   * Verifies a parsed, signed token.
   *
   * @throws ApiException {@code 401} if the token is not valid; {@code 503} if the issuer's keys
   *                      cannot be fetched, or the token names a key the server cannot fetch yet.
   */
  Caller verify(final SignedJWT token) {
    final String subject;
    try {
      subject = processor.process(token, null).getSubject();
    } catch (final RateLimitReachedException e) {
      // The token names a kid the cached JWKS lacks, and the JWKS was fetched too recently to
      // fetch again. During a real key rotation the key appears once the window passes, so the
      // client should retry later with the token it has, not get a new one.
      LOG.info("Token from issuer {} names an unknown key; JWKS refresh is rate limited", name);
      throw ApiException.unavailable("The token's signing key is not known yet", keyRefreshSeconds);
    } catch (final KeySourceException e) {
      LOG.warn("Cannot get the signing keys of issuer {}: {}", name, e.getMessage());
      throw ApiException.unavailable("The token issuer cannot be reached", retryAfterSeconds);
    } catch (final BadJWTException e) {
      LOG.debug("Refused a token from issuer {}: {}", name, e.getMessage());
      final boolean expired = e.getMessage() != null && e.getMessage().startsWith("Expired");
      throw ApiException.invalidToken(expired ? "The access token expired" : "The access token is not valid");
    } catch (final BadJOSEException | JOSEException e) {
      LOG.debug("Refused a token from issuer {}: {}", name, e.getMessage());
      throw ApiException.invalidToken("The access token is not valid");
    }
    if (subject == null || subject.isBlank()) {
      throw ApiException.invalidToken("The access token is not valid");
    }
    return new Caller(name, subject);
  }
}
