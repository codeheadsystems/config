package com.codeheadsystems.config.server;

import com.codeheadsystems.config.server.auth.IssuerUrls;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.validation.ValidationMethod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;

/** One trusted OpenID Connect issuer. */
public class IssuerConfig {

  @NotNull
  @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,62}",
      message = "must be 1 to 63 lowercase letters, digits, '-' or '_', starting with a letter or digit")
  private String name;

  @NotNull
  private URI issuer;

  @NotEmpty
  private String audience;

  /**
   * The issuer's short name, used in METADATA: {@code auth.<name>.subjects}.
   *
   * @return the name.
   */
  @JsonProperty
  public String getName() {
    return name;
  }

  /**
   * Sets the issuer's short name.
   *
   * @param name the name.
   */
  @JsonProperty
  public void setName(final String name) {
    this.name = name;
  }

  /**
   * The issuer identifier; a token's {@code iss} must equal it exactly. Discovery is at this URL
   * plus {@code /.well-known/openid-configuration}.
   *
   * @return the issuer.
   */
  @JsonProperty
  public URI getIssuer() {
    return issuer;
  }

  /**
   * Sets the issuer identifier.
   *
   * @param issuer the issuer.
   */
  @JsonProperty
  public void setIssuer(final URI issuer) {
    this.issuer = issuer;
  }

  /**
   * The audience that identifies this service; a token's {@code aud} must contain it.
   *
   * @return the audience.
   */
  @JsonProperty
  public String getAudience() {
    return audience;
  }

  /**
   * Sets the audience.
   *
   * @param audience the audience.
   */
  @JsonProperty
  public void setAudience(final String audience) {
    this.audience = audience;
  }

  /**
   * Checked at startup: keys are fetched from the issuer, so it must be https (http only on
   * loopback, for local development and tests).
   *
   * @return whether the issuer URL is acceptable.
   */
  @JsonIgnore
  @ValidationMethod(message = "issuer must be an absolute https URL (http only for localhost, 127.0.0.1 or ::1)")
  public boolean isSecureIssuer() {
    return issuer == null || IssuerUrls.isSecure(issuer);
  }
}
