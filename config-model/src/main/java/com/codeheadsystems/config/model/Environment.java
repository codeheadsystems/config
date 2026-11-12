package com.codeheadsystems.config.model;

import java.util.Objects;

public record Environment(String value) {

  public Environment {
    Objects.requireNonNull(value, "value cannot be null");
  }

  public static Environment from(String value) {
    return new Environment(value);
  }

}
