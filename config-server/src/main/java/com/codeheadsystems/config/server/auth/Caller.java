package com.codeheadsystems.config.server.auth;

import java.security.Principal;
import java.util.Objects;

/**
 * Who made a request, from a verified token.
 *
 * @param issuer  the trusted issuer's configured name, such as {@code k8s-prod}.
 * @param subject the token's {@code sub}.
 */
public record Caller(String issuer, String subject) implements Principal {

  /**
   * Checks both parts are present.
   *
   * @param issuer  the issuer name.
   * @param subject the subject.
   */
  public Caller {
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(subject, "subject");
  }

  @Override
  public String getName() {
    return subject;
  }
}
