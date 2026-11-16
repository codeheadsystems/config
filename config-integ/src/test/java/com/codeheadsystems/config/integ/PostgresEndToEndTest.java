package com.codeheadsystems.config.integ;

import java.util.Map;

/** The whole path on PostgreSQL. */
class PostgresEndToEndTest extends EndToEndContract {

  @Override
  protected String datastore() {
    return "sql";
  }

  @Override
  protected Map<String, String> options() {
    return Backends.postgres();
  }
}
