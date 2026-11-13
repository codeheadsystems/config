package com.codeheadsystems.config.model;

import java.util.regex.Pattern;

/**
 * Where an application is running, such as {@code prod} or {@code test}.
 *
 * @param value the name: 1 to 63 lowercase letters, digits, {@code -} or {@code _}, starting with
 *              a letter or digit.
 */
public record Environment(String value) {

  /** The pattern a valid name matches. */
  public static final Pattern VALIDATOR = Names.VALIDATOR;

  /**
   * Validates the name.
   *
   * @param value the name.
   * @throws NullPointerException     if the name is null.
   * @throws IllegalArgumentException if the name does not match {@link #VALIDATOR}.
   */
  public Environment {
    Names.requireValid(value, "environment");
  }

  /**
   * Creates an environment.
   *
   * @param value the name.
   * @return the environment.
   */
  public static Environment from(final String value) {
    return new Environment(value);
  }
}
