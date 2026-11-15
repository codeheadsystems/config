package com.codeheadsystems.config.server.auth;

import com.codeheadsystems.config.server.api.ApiException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.Locale;

/**
 * Requires a valid bearer token on every request, before any resource runs, so a bad token is
 * {@code 401} whatever else is wrong with the request. A valid token is then rate limited
 * ({@code 429}), and its {@link Caller} becomes the request's principal.
 */
@Singleton
@Priority(Priorities.AUTHENTICATION)
public final class BearerTokenFilter implements ContainerRequestFilter {

  private static final String SCHEME = "bearer ";

  private final TokenVerifier verifier;
  private final RateLimiter limiter;

  /**
   * Creates the filter.
   *
   * @param verifier checks tokens.
   * @param limiter  limits requests per token.
   */
  @Inject
  public BearerTokenFilter(final TokenVerifier verifier, final RateLimiter limiter) {
    this.verifier = verifier;
    this.limiter = limiter;
  }

  @Override
  public void filter(final ContainerRequestContext request) {
    final String authorization = request.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (authorization == null
        || !authorization.toLowerCase(Locale.ROOT).startsWith(SCHEME)
        || authorization.substring(SCHEME.length()).isBlank()) {
      throw ApiException.missingToken();
    }
    final String token = authorization.substring(SCHEME.length()).trim();
    final Caller caller = verifier.verify(token);
    // After verification, so only real tokens get buckets; a forged token is already a 401.
    limiter.acquire(token);
    request.setSecurityContext(new CallerSecurityContext(caller, request.getSecurityContext().isSecure()));
  }
}
