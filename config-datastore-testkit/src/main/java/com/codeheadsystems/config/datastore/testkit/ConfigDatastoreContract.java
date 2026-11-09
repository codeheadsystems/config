package com.codeheadsystems.config.datastore.testkit;

import com.codeheadsystems.config.datastore.ConfigDatastore;

/**
 * The contract every {@link ConfigDatastore} must honour. A backend's test class extends this and
 * supplies its datastore; the inherited tests do the rest.
 */
public abstract class ConfigDatastoreContract {

  /**
   * For subclasses.
   */
  protected ConfigDatastoreContract() {
  }

  /**
   * Supplies the datastore under test.
   *
   * @return a datastore backed by a fresh, empty store.
   */
  protected abstract ConfigDatastore datastore();
}
