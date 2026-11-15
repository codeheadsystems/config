package com.codeheadsystems.config.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Caching inside the server, and the {@code Cache-Control} values sent to clients when an
 * application's METADATA does not set its own ({@code cache.max-age}, {@code cache.stale-if-error}).
 */
public class CacheConfig {

  @NotNull
  private Duration versionTtl = Duration.seconds(1);

  @NotNull
  private Duration metadataTtl = Duration.seconds(10);

  @Min(0)
  @Max(Integer.MAX_VALUE)
  private long defaultMaxAge = 30;

  @Min(0)
  @Max(Integer.MAX_VALUE)
  private long defaultStaleIfError = 86_400;

  @Min(0)
  @Max(Integer.MAX_VALUE)
  private long retryAfter = 30;

  /**
   * How long the server reuses a set's version, so a burst of version checks costs one datastore
   * query. Clients may see a change this much later.
   *
   * @return the TTL; 1 second by default.
   */
  @JsonProperty
  public Duration getVersionTtl() {
    return versionTtl;
  }

  /**
   * Sets the version TTL.
   *
   * @param versionTtl the TTL.
   */
  @JsonProperty
  public void setVersionTtl(final Duration versionTtl) {
    this.versionTtl = versionTtl;
  }

  /**
   * How long the server reuses an application's METADATA (access lists and cache settings).
   * Granting or revoking access takes effect within this time.
   *
   * @return the TTL; 10 seconds by default.
   */
  @JsonProperty
  public Duration getMetadataTtl() {
    return metadataTtl;
  }

  /**
   * Sets the METADATA TTL.
   *
   * @param metadataTtl the TTL.
   */
  @JsonProperty
  public void setMetadataTtl(final Duration metadataTtl) {
    this.metadataTtl = metadataTtl;
  }

  /**
   * {@code max-age} (and {@code stale-while-revalidate}) in seconds, unless METADATA sets
   * {@code cache.max-age}.
   *
   * @return seconds; 30 by default.
   */
  @JsonProperty
  public long getDefaultMaxAge() {
    return defaultMaxAge;
  }

  /**
   * Sets the default {@code max-age}.
   *
   * @param defaultMaxAge seconds.
   */
  @JsonProperty
  public void setDefaultMaxAge(final long defaultMaxAge) {
    this.defaultMaxAge = defaultMaxAge;
  }

  /**
   * {@code stale-if-error} in seconds, unless METADATA sets {@code cache.stale-if-error}.
   *
   * @return seconds; 86400 (one day) by default.
   */
  @JsonProperty
  public long getDefaultStaleIfError() {
    return defaultStaleIfError;
  }

  /**
   * Sets the default {@code stale-if-error}.
   *
   * @param defaultStaleIfError seconds.
   */
  @JsonProperty
  public void setDefaultStaleIfError(final long defaultStaleIfError) {
    this.defaultStaleIfError = defaultStaleIfError;
  }

  /**
   * {@code Retry-After}, in seconds, sent with {@code 503} when the datastore or an issuer cannot
   * be reached.
   *
   * @return seconds; 30 by default.
   */
  @JsonProperty
  public long getRetryAfter() {
    return retryAfter;
  }

  /**
   * Sets {@code Retry-After}.
   *
   * @param retryAfter seconds.
   */
  @JsonProperty
  public void setRetryAfter(final long retryAfter) {
    this.retryAfter = retryAfter;
  }
}
