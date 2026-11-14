package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.testkit.ConfigDatastoreContract;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the datastore contract against PostgreSQL. Skipped without Docker, except on CI, where it
 * fails instead.
 */
class SqlConfigDatastoreContractTest extends ConfigDatastoreContract {

  @BeforeAll
  static void requireDocker() {
    Postgres.requireDocker();
  }

  @Override
  protected ConfigDatastore datastore() {
    return new SqlConfigDatastore(Postgres.migratedSchema(), 64 * 1024);
  }
}
