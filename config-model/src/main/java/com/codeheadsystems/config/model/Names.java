package com.codeheadsystems.config.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The rule shared by {@link Application} and {@link Environment} names: 1 to 63 characters of
 * lowercase letters, digits, {@code -} and {@code _}, starting with a letter or digit, like a DNS
 * label. Lowercase only, so two names can never differ just by case (some datastores compare
 * case-insensitively), and no {@code /} or {@code .}, so a name is always safe as one segment of a
 * path, URL or storage key.
 */
final class Names {

  static final Pattern VALIDATOR = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

  private Names() {
  }

  static String requireValid(final String value, final String what) {
    Objects.requireNonNull(value, what + " must not be null");
    if (!VALIDATOR.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid " + what + " '" + value
        + "': use 1 to 63 lowercase letters, digits, '-' or '_', starting with a letter or digit");
    }
    return value;
  }
}
