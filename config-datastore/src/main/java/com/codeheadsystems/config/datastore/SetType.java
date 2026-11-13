package com.codeheadsystems.config.datastore;

/**
 * The kinds of set stored for each application and environment.
 */
public enum SetType {

  /**
   * The values served to the application, through config-client or config-library. This is the
   * only type an application ever reads.
   */
  SETTINGS,

  /**
   * Information the config service keeps about the application in this environment. Never served
   * to the application.
   */
  METADATA
}
