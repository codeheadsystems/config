package com.codeheadsystems.config.datastore.dynamodb;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.testkit.ConfigDatastoreContract;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the datastore contract against DynamoDB Local. Skipped without Docker, except on CI, where
 * it fails instead.
 */
class DynamodbConfigDatastoreContractTest extends ConfigDatastoreContract {

  @BeforeAll
  static void requireDocker() {
    DynamodbLocal.requireDocker();
  }

  @Override
  protected ConfigDatastore datastore() {
    return new DynamodbConfigDatastore(DynamodbLocal.client(), DynamodbLocal.freshTable(), 64 * 1024);
  }
}
