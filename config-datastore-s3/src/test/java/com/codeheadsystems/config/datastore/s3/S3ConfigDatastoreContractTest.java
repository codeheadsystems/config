package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.testkit.ConfigDatastoreContract;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the datastore contract against RustFS. Skipped without Docker, except on CI, where it fails instead.
 */
class S3ConfigDatastoreContractTest extends ConfigDatastoreContract {

  @BeforeAll
  static void requireDocker() {
    RustFs.requireDocker();
  }

  @Override
  protected ConfigDatastore datastore() {
    return new S3ConfigDatastore(RustFs.client(), RustFs.BUCKET, RustFs.freshPrefix(), 64 * 1024);
  }
}
