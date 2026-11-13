package com.codeheadsystems.config.model;

import java.util.regex.Pattern;

/**
 * The service that is running and reading its configuration, identified by its service name,
 * such as {@code billing}.
 *
 * @param value the name: 1 to 63 lowercase letters, digits, {@code -} or {@code _}, starting with
 *              a letter or digit.
 */
public record Application(String value) {

  /** The pattern a valid name matches. */
  public static final Pattern VALIDATOR = Names.VALIDATOR;

  /**
   * Validates the name.
   *
   * @param value the name.
   * @throws NullPointerException     if the name is null.
   * @throws IllegalArgumentException if the name does not match {@link #VALIDATOR}.
   */
  public Application {
    Names.requireValid(value, "application");
  }

  /**
   * Creates an application.
   *
   * @param value the name.
   * @return the application.
   */
  public static Application from(final String value) {
    return new Application(value);
  }
}
