package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Configuration;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds datastore backends on the classpath and opens them by name.
 *
 * <pre>{@code
 * try (ConfigDatastore datastore = ConfigDatastores.open("postgresql", bootstrap.with("postgresql"))) {
 *   ...
 * }
 * }</pre>
 */
public final class ConfigDatastores {

  private ConfigDatastores() {
  }

  /**
   * The names of the backends on the classpath.
   *
   * @return the names, sorted.
   */
  public static Set<String> available() {
    return providers().keySet();
  }

  /**
   * Opens a backend.
   *
   * @param name    the backend's name.
   * @param options the backend's options.
   * @return the datastore.
   * @throws ConfigDatastoreException if no backend has that name.
   */
  public static ConfigDatastore open(final String name, final Configuration options) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(options, "options must not be null");
    final Map<String, ConfigDatastoreProvider> providers = providers();
    final ConfigDatastoreProvider provider = providers.get(name);
    if (provider == null) {
      throw new ConfigDatastoreException("No datastore named '" + name + "'; available: " + providers.keySet());
    }
    return provider.create(options);
  }

  private static Map<String, ConfigDatastoreProvider> providers() {
    final Map<String, ConfigDatastoreProvider> providers = new TreeMap<>();
    for (final ConfigDatastoreProvider provider : ServiceLoader.load(ConfigDatastoreProvider.class)) {
      final ConfigDatastoreProvider existing = providers.putIfAbsent(provider.name(), provider);
      if (existing != null) {
        throw new ConfigDatastoreException("Two datastores are named '" + provider.name() + "': "
          + existing.getClass().getName() + " and " + provider.getClass().getName());
      }
    }
    return providers;
  }
}
