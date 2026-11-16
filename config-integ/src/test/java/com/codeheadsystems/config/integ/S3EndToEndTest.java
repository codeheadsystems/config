package com.codeheadsystems.config.integ;

import java.util.Map;

/** The whole path on RustFS (S3). */
class S3EndToEndTest extends EndToEndContract {

  @Override
  protected String datastore() {
    return "s3";
  }

  @Override
  protected Map<String, String> options() {
    return Backends.s3();
  }
}
