package com.codeheadsystems.config.server;

import com.codahale.metrics.health.HealthCheck;
import com.codeheadsystems.config.server.auth.TokenVerifier;
import com.nimbusds.jose.KeySourceException;

/**
 * Healthy when an issuer's discovery document and keys can be fetched (or are still cached), so a
 * misconfigured or unreachable issuer shows up here rather than as {@code 503}s once clients call.
 */
final class IssuerHealthCheck extends HealthCheck {

  private final TokenVerifier verifier;
  private final String issuer;

  IssuerHealthCheck(final TokenVerifier verifier, final String issuer) {
    this.verifier = verifier;
    this.issuer = issuer;
  }

  @Override
  protected Result check() {
    try {
      verifier.check(issuer);
      return Result.healthy();
    } catch (final KeySourceException e) {
      return Result.unhealthy(e.getMessage());
    }
  }
}
