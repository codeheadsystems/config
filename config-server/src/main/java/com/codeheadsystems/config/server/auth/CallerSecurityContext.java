package com.codeheadsystems.config.server.auth;

import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;

/** Exposes the verified {@link Caller} to resources as the request's principal. */
final class CallerSecurityContext implements SecurityContext {

  private final Caller caller;
  private final boolean secure;

  CallerSecurityContext(final Caller caller, final boolean secure) {
    this.caller = caller;
    this.secure = secure;
  }

  @Override
  public Principal getUserPrincipal() {
    return caller;
  }

  @Override
  public boolean isUserInRole(final String role) {
    return false;
  }

  @Override
  public boolean isSecure() {
    return secure;
  }

  @Override
  public String getAuthenticationScheme() {
    return "Bearer";
  }
}
