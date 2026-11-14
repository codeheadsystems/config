package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Configuration;

/**
 * Creates one kind of {@link ConfigDatastore}. Each backend registers an implementation through
 * {@link java.util.ServiceLoader}, in
 * {@code META-INF/services/com.codeheadsystems.config.datastore.ConfigDatastoreProvider}.
 */
public interface ConfigDatastoreProvider {

  /**
   * The backend's name, which {@link ConfigDatastores#open} selects it by. Must be unique on the
   * classpath.
   *
   * @return the name.
   */
  String name();

  /**
   * Creates a datastore.
   *
   * @param options the backend's options, such as connection details; each backend documents its
   *                own keys.
   * @return the datastore.
   */
  ConfigDatastore create(Configuration options);
}
