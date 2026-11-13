package com.codeheadsystems.config.datastore.testkit;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.memory.InMemoryConfigDatastore;

/**
 * Runs the contract against the in-memory backend, with a small limit so the size tests are quick.
 */
class InMemoryConfigDatastoreContractTest extends ConfigDatastoreContract {

  @Override
  protected ConfigDatastore datastore() {
    return new InMemoryConfigDatastore(4096);
  }
}
