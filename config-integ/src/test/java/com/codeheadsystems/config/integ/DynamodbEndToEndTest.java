package com.codeheadsystems.config.integ;

import java.util.Map;

/** The whole path on DynamoDB Local. */
class DynamodbEndToEndTest extends EndToEndContract {

  @Override
  protected String datastore() {
    return "dynamodb";
  }

  @Override
  protected Map<String, String> options() {
    return Backends.dynamodb();
  }
}
