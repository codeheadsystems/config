package com.codeheadsystems.config.datastore.testkit;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.memory.InMemoryConfigDatastore;

/**
 * Runs the contract against the in-memory backend with its default limit, so the tests that need
 * larger sets run too.
 */
class InMemoryDefaultLimitContractTest extends ConfigDatastoreContract {

  @Override
  protected ConfigDatastore datastore() {
    return new InMemoryConfigDatastore();
  }
}
