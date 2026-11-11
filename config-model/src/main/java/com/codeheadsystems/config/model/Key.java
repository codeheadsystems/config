package com.codeheadsystems.config.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record Key(String name) {
  public static final Pattern VALIDATOR = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");
  public static final String SEPARATOR = ".";

  public Key {
    Objects.requireNonNull(name, "name cannot be null");
    // The pattern also rejects empty and blank names, so every invalid key gets the same message.
    if (!VALIDATOR.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid key '" + name
          + "': use letters, digits, '_' or '-', with '" + SEPARATOR + "' only between segments");
    }
  }

  public static Key from(String name) {
    return new Key(name);
  }

  public Key and(String key) {
    return from(this.name + SEPARATOR + key);
  }

  public Key and(Key name) {
    return from(this.name + SEPARATOR + name.name());
  }

}
