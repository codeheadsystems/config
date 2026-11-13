package com.codeheadsystems.config.datastore.memory;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastoreProvider;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;

/**
 * Provides {@link InMemoryConfigDatastore} under the name {@value #NAME}.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code max-set-bytes}: the size limit; defaults to
 *   {@link InMemoryConfigDatastore#DEFAULT_MAX_SET_BYTES}.</li>
 * </ul>
 */
public final class InMemoryConfigDatastoreProvider implements ConfigDatastoreProvider {

  /**
   * The backend's name.
   */
  public static final String NAME = "memory";

  /**
   * Creates the provider. Called by {@link java.util.ServiceLoader}.
   */
  public InMemoryConfigDatastoreProvider() {
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ConfigDatastore create(final Configuration options) {
    final long maxSetBytes = options.find("max-set-bytes")
      .map(Value::value)
      .map(InMemoryConfigDatastoreProvider::parseLong)
      .orElse(InMemoryConfigDatastore.DEFAULT_MAX_SET_BYTES);
    return new InMemoryConfigDatastore(maxSetBytes);
  }

  private static long parseLong(final String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("The memory datastore's 'max-set-bytes' must be a number: '" + value + "'", e);
    }
  }
}
