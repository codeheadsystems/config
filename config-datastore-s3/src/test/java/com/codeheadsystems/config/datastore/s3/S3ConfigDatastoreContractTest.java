package com.codeheadsystems.config.datastore.s3;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.testkit.ConfigDatastoreContract;
import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the datastore contract against RustFS. Skipped when Docker is not available.
 */
class S3ConfigDatastoreContractTest extends ConfigDatastoreContract {

  @BeforeAll
  static void requireDocker() {
    assumeTrue(RustFs.dockerAvailable(), "Docker is not available");
  }

  @Override
  protected ConfigDatastore datastore() {
    return new S3ConfigDatastore(RustFs.client(), RustFs.BUCKET, RustFs.freshPrefix(), 64 * 1024);
  }
}
