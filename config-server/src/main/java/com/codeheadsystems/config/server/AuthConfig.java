package com.codeheadsystems.config.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.validation.MinDuration;
import io.dropwizard.validation.ValidationMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The OpenID Connect issuers whose bearer tokens the server accepts.
 *
 * <pre>
 * auth:
 *   issuers:
 *     - name: k8s-prod
 *       issuer: https://oidc.example.com
 *       audience: config-server
 *   clockSkew: 60s
 * </pre>
 */
public class AuthConfig {

  @NotNull
  private List<@NotNull @Valid IssuerConfig> issuers = List.of();

  @NotNull
  @MaxDuration(value = 10, unit = TimeUnit.MINUTES)
  private Duration clockSkew = Duration.seconds(60);

  @NotNull
  @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
  @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
  private Duration timeout = Duration.seconds(5);

  @NotNull
  @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
  private Duration keyRefreshInterval = Duration.seconds(30);

  @NotNull
  @MinDuration(value = 1, unit = TimeUnit.MINUTES)
  private Duration keyCacheTtl = Duration.minutes(5);

  @NotNull
  private Duration keyOutageTolerance = Duration.days(1);

  /**
   * The trusted issuers. With none, every request is refused with {@code 401}.
   *
   * @return the issuers.
   */
  @JsonProperty
  public List<IssuerConfig> getIssuers() {
    return issuers;
  }

  /**
   * Sets the trusted issuers.
   *
   * @param issuers the issuers; null fails validation.
   */
  @JsonProperty
  public void setIssuers(final List<IssuerConfig> issuers) {
    this.issuers = issuers == null ? null : Collections.unmodifiableList(new ArrayList<>(issuers));
  }

  /**
   * How far {@code exp} and {@code nbf} may be off, for clocks that disagree.
   *
   * @return the allowed skew; 60 seconds by default, at most 10 minutes.
   */
  @JsonProperty
  public Duration getClockSkew() {
    return clockSkew;
  }

  /**
   * Sets the allowed clock skew.
   *
   * @param clockSkew the skew.
   */
  @JsonProperty
  public void setClockSkew(final Duration clockSkew) {
    this.clockSkew = clockSkew;
  }

  /**
   * Connect and read timeout for an issuer's discovery document and JWKS. Must be positive: a
   * hung issuer must not hold request threads.
   *
   * @return the timeout; 5 seconds by default.
   */
  @JsonProperty
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Sets the issuer timeout.
   *
   * @param timeout the timeout.
   */
  @JsonProperty
  public void setTimeout(final Duration timeout) {
    this.timeout = timeout;
  }

  /**
   * The window for fetching an issuer's JWKS again when tokens name a {@code kid} it does not
   * have: at most two fetches per window, so tokens with made-up key IDs cannot make the server
   * hammer the issuer. A token refused by this limit gets {@code 503} with {@code Retry-After}.
   *
   * @return the interval; 30 seconds by default.
   */
  @JsonProperty
  public Duration getKeyRefreshInterval() {
    return keyRefreshInterval;
  }

  /**
   * Sets the key refresh interval.
   *
   * @param keyRefreshInterval the interval.
   */
  @JsonProperty
  public void setKeyRefreshInterval(final Duration keyRefreshInterval) {
    this.keyRefreshInterval = keyRefreshInterval;
  }

  /**
   * How long an issuer's JWKS is cached before it is fetched again (it is also refreshed shortly
   * before it expires, and whenever a token names an unknown {@code kid}).
   *
   * @return the TTL; 5 minutes by default, at least 1 minute.
   */
  @JsonProperty
  public Duration getKeyCacheTtl() {
    return keyCacheTtl;
  }

  /**
   * Sets the JWKS cache TTL.
   *
   * @param keyCacheTtl the TTL.
   */
  @JsonProperty
  public void setKeyCacheTtl(final Duration keyCacheTtl) {
    this.keyCacheTtl = keyCacheTtl;
  }

  /**
   * Checked at startup, since the key library would only refuse it on the first token.
   *
   * @return whether the refresh interval is shorter than the key cache TTL.
   */
  @JsonIgnore
  @ValidationMethod(message = "keyRefreshInterval must be shorter than keyCacheTtl")
  public boolean isKeyRefreshShorterThanKeyCache() {
    return keyRefreshInterval == null || keyCacheTtl == null
        || keyRefreshInterval.toMilliseconds() < keyCacheTtl.toMilliseconds();
  }

  /**
   * How long the server keeps using keys it already fetched while an issuer cannot be reached,
   * after its key cache ({@link #getKeyCacheTtl()}) expires. {@code 0s} turns this off.
   *
   * @return the tolerance; 1 day by default, the same as the default {@code stale-if-error}.
   */
  @JsonProperty
  public Duration getKeyOutageTolerance() {
    return keyOutageTolerance;
  }

  /**
   * Sets the key outage tolerance.
   *
   * @param keyOutageTolerance the tolerance.
   */
  @JsonProperty
  public void setKeyOutageTolerance(final Duration keyOutageTolerance) {
    this.keyOutageTolerance = keyOutageTolerance;
  }
}
