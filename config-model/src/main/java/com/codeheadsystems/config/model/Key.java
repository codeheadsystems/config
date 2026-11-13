package com.codeheadsystems.config.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A configuration key: one or more segments joined by {@value #SEPARATOR}, such as
 * {@code database.pool.size}. Each segment is letters, digits, {@code _} or {@code -}. Keys are
 * case-sensitive.
 *
 * @param name the key.
 */
public record Key(String name) {

  /** The pattern a valid key matches. */
  public static final Pattern VALIDATOR = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

  /** Separates the segments of a key, and a namespace from the keys inside it. */
  public static final String SEPARATOR = ".";

  /**
   * Validates the key.
   *
   * @param name the key.
   * @throws NullPointerException     if the key is null.
   * @throws IllegalArgumentException if the key does not match {@link #VALIDATOR}.
   */
  public Key {
    Objects.requireNonNull(name, "name cannot be null");
    // The pattern also rejects empty and blank names, so every invalid key gets the same message.
    if (!VALIDATOR.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid key '" + name
        + "': use letters, digits, '_' or '-', with '" + SEPARATOR + "' only between segments");
    }
  }

  /**
   * Creates a key.
   *
   * @param name the key.
   * @return the key.
   * @throws IllegalArgumentException if the key is not valid.
   */
  public static Key from(final String name) {
    return new Key(name);
  }

  /**
   * This key followed by more segments: {@code database} and {@code url} give {@code database.url}.
   *
   * @param key the segments to add.
   * @return the combined key.
   */
  public Key and(final String key) {
    return from(this.name + SEPARATOR + key);
  }

  /**
   * This key followed by another: {@code database} and {@code pool.size} give
   * {@code database.pool.size}.
   *
   * @param key the key to add.
   * @return the combined key.
   */
  public Key and(final Key key) {
    return from(this.name + SEPARATOR + key.name());
  }
}
