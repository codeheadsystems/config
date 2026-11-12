package com.codeheadsystems.config.model;

import java.util.Objects;

public record Application(String value) {

  public Application {
    Objects.requireNonNull(value, "value cannot be null");
  }

  public static Application from(String value) {
    return new Application(value);
  }

}
