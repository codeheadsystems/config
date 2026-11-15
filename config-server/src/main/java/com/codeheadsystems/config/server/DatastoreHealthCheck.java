package com.codeheadsystems.config.server;

import com.codahale.metrics.health.HealthCheck;
import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ValidationResult;
import java.util.stream.Collectors;

/** Healthy when the datastore passes {@code validate(Access.READ)}. */
final class DatastoreHealthCheck extends HealthCheck {

  private final ConfigDatastore datastore;

  DatastoreHealthCheck(final ConfigDatastore datastore) {
    this.datastore = datastore;
  }

  @Override
  protected Result check() {
    final ValidationResult result = datastore.validate(Access.READ);
    if (result.ok()) {
      return Result.healthy();
    }
    return Result.unhealthy(result.failures().stream()
        .map(check -> check.name() + ": " + check.detail())
        .collect(Collectors.joining("; ")));
  }
}
