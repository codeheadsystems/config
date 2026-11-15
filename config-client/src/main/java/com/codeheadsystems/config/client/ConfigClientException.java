package com.codeheadsystems.config.client;

import java.io.Serial;

/** The config service could not provide the settings when they were required. */
public class ConfigClientException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed.
   */
  public ConfigClientException(final String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed.
   * @param cause   why.
   */
  public ConfigClientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
