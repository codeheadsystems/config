package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import java.util.Objects;

/**
 * Identifies one set in a datastore: an application, an environment, and the type of set.
 *
 * @param application the application.
 * @param environment the environment.
 * @param type        the type of set.
 */
public record SetId(Application application, Environment environment, SetType type) {

  /**
   * Validates the identifier.
   *
   * @param application the application.
   * @param environment the environment.
   * @param type        the type of set.
   */
  public SetId {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(type, "type must not be null");
  }

  /**
   * The {@link SetType#SETTINGS} set for an application in an environment.
   *
   * @param application the application.
   * @param environment the environment.
   * @return the identifier.
   */
  public static SetId settings(final Application application, final Environment environment) {
    return new SetId(application, environment, SetType.SETTINGS);
  }

  /**
   * The {@link SetType#METADATA} set for an application in an environment.
   *
   * @param application the application.
   * @param environment the environment.
   * @return the identifier.
   */
  public static SetId metadata(final Application application, final Environment environment) {
    return new SetId(application, environment, SetType.METADATA);
  }
}
