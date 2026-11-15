package com.codeheadsystems.config.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Requests allowed per bearer token, as a token bucket: {@code burst} requests at once, refilled
 * at {@code requestsPerMinute}. Each token (each client instance) has its own bucket, so many
 * instances sharing one identity are not throttled together. Over the limit is {@code 429} with
 * {@code Retry-After}.
 */
public class RateLimitConfig {

  @Min(1)
  @Max(1_000_000)
  private int requestsPerMinute = 60;

  @Min(1)
  @Max(1_000_000)
  private int burst = 20;

  /**
   * The sustained rate per token.
   *
   * @return requests per minute; 60 by default.
   */
  @JsonProperty
  public int getRequestsPerMinute() {
    return requestsPerMinute;
  }

  /**
   * Sets the sustained rate.
   *
   * @param requestsPerMinute requests per minute.
   */
  @JsonProperty
  public void setRequestsPerMinute(final int requestsPerMinute) {
    this.requestsPerMinute = requestsPerMinute;
  }

  /**
   * How many requests a token may make at once before the rate applies.
   *
   * @return the burst; 20 by default.
   */
  @JsonProperty
  public int getBurst() {
    return burst;
  }

  /**
   * Sets the burst.
   *
   * @param burst the burst.
   */
  @JsonProperty
  public void setBurst(final int burst) {
    this.burst = burst;
  }
}
