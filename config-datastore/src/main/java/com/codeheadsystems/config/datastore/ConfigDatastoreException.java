package com.codeheadsystems.config.datastore;

import java.io.Serial;

/**
 * A datastore operation failed. Backends wrap their driver's exceptions in this.
 */
public class ConfigDatastoreException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed.
   */
  public ConfigDatastoreException(final String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed.
   * @param cause   the underlying failure.
   */
  public ConfigDatastoreException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
