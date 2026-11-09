package com.codeheadsystems.config.model;

import java.util.Objects;

/**
 * The coordinates of one configuration set: an application in an environment.
 *
 * @param application the application name.
 * @param environment the environment name, such as {@code dev} or {@code prod}.
 */
public record AppEnvironment(String application, String environment) {

  /**
   * Validates the coordinates.
   *
   * @param application the application name.
   * @param environment the environment name.
   */
  public AppEnvironment {
    Objects.requireNonNull(application, "application");
    Objects.requireNonNull(environment, "environment");
  }
}
