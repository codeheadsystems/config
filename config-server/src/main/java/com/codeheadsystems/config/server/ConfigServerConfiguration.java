package com.codeheadsystems.config.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The server's configuration file: Dropwizard's own sections ({@code server}, {@code logging},
 * ...) plus the datastore to serve, the issuers to trust, caching, and the
 * per-token rate limit.
 */
public class ConfigServerConfiguration extends Configuration {

  @Valid
  @NotNull
  private DatastoreConfig datastore = new DatastoreConfig();

  @Valid
  @NotNull
  private AuthConfig auth = new AuthConfig();

  @Valid
  @NotNull
  private CacheConfig cache = new CacheConfig();

  @Valid
  @NotNull
  private RateLimitConfig rateLimit = new RateLimitConfig();

  /**
   * The datastore the settings are read from.
   *
   * @return the datastore section.
   */
  @JsonProperty
  public DatastoreConfig getDatastore() {
    return datastore;
  }

  /**
   * Sets the datastore section.
   *
   * @param datastore the datastore section.
   */
  @JsonProperty
  public void setDatastore(final DatastoreConfig datastore) {
    this.datastore = datastore;
  }

  /**
   * The OpenID Connect issuers whose tokens are accepted.
   *
   * @return the auth section.
   */
  @JsonProperty
  public AuthConfig getAuth() {
    return auth;
  }

  /**
   * Sets the auth section.
   *
   * @param auth the auth section.
   */
  @JsonProperty
  public void setAuth(final AuthConfig auth) {
    this.auth = auth;
  }

  /**
   * Server-side caching and the cache headers sent to clients.
   *
   * @return the cache section.
   */
  @JsonProperty
  public CacheConfig getCache() {
    return cache;
  }

  /**
   * Sets the cache section.
   *
   * @param cache the cache section.
   */
  @JsonProperty
  public void setCache(final CacheConfig cache) {
    this.cache = cache;
  }

  /**
   * The per-token request limit.
   *
   * @return the rate limit section.
   */
  @JsonProperty
  public RateLimitConfig getRateLimit() {
    return rateLimit;
  }

  /**
   * Sets the rate limit section.
   *
   * @param rateLimit the rate limit section.
   */
  @JsonProperty
  public void setRateLimit(final RateLimitConfig rateLimit) {
    this.rateLimit = rateLimit;
  }
}
