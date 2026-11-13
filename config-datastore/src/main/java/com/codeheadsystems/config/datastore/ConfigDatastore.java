package com.codeheadsystems.config.datastore;

/**
 * A backend that stores configuration sets. Implementations live in the {@code config-datastore-*}
 * modules and are found through {@link ConfigDatastoreProvider} and {@link ConfigDatastores}.
 *
 * <p>Every implementation must pass the contract tests in config-datastore-testkit.
 */
public interface ConfigDatastore extends ConfigReader, ConfigWriter, AutoCloseable {

  /**
   * Checks that this datastore is configured correctly for the given access: that the store is
   * reachable, the credentials have the permissions the access needs, and the store behaves as
   * the contract requires (for example, that it enforces conditional writes). Run it at startup
   * to fail with a clear reason instead of on the first read or write.
   *
   * <p>Checking {@link Access#READ_WRITE} may write and remove a probe object outside the stored
   * sets; it never changes a set.
   *
   * @param access what the caller needs to do.
   * @return every check made; see {@link ValidationResult#requireOk()}.
   */
  ValidationResult validate(Access access);

  /**
   * Releases the backend's connections. Does nothing by default.
   */
  @Override
  default void close() {
  }
}
