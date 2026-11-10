package com.codeheadsystems.config.model;

import java.util.Objects;

public record Namespace(String name) {

  public static final String SEPARATOR = ".";

  public Namespace {
    Objects.requireNonNull(name, "name must not be null");
  }

  public static Namespace from(String name) {
    return new Namespace(name);
  }

  public Namespace and(String name) {
    return from(this.name + SEPARATOR + name);
  }

  public Namespace and(Namespace name) {
    return from(this.name + SEPARATOR + name.name());
  }
}
