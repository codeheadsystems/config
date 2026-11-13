package com.codeheadsystems.config.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A configuration value. Any string, including the empty string, is a value; a missing key has no
 * value at all.
 *
 * @param value the value.
 */
public record Value(String value) {

  /**
   * Validates the value.
   *
   * @param value the value.
   * @throws NullPointerException if the value is null.
   */
  public Value {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * Wraps a possibly-null string, as returned by {@link java.util.Map#get}.
   *
   * @param value the string, or null.
   * @return the value, or empty if the string is null.
   */
  public static Optional<Value> from(final String value) {
    return value == null ? Optional.empty() : Optional.of(new Value(value));
  }
}
