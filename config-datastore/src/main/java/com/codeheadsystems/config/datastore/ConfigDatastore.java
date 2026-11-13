package com.codeheadsystems.config.datastore;

/**
 * A backend that stores configuration sets. Implementations live in the {@code config-datastore-*}
 * modules and are found through {@link ConfigDatastoreProvider} and {@link ConfigDatastores}.
 *
 * <p>Every implementation must pass the contract tests in config-datastore-testkit.
 */
public interface ConfigDatastore extends ConfigReader, ConfigWriter, AutoCloseable {

  /**
   * Releases the backend's connections. Does nothing by default.
   */
  @Override
  default void close() {
  }
}
