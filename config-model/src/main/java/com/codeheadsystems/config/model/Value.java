package com.codeheadsystems.config.model;

import java.util.Objects;
import java.util.Optional;

public record Value(String value) {

  public Value {
    Objects.requireNonNull(value, "name must not be null");
  }

  public static Optional<Value> from(String value) {
    if (value == null) {
      return Optional.empty();
    } else {
      return Optional.of(new Value(value));
    }
  }

}
