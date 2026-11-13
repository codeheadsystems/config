package com.codeheadsystems.config.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A test-only {@link ConfigurationFactory} standing in for a real datastore backend. Its builder
 * extends {@link ConfigurationFactory.Builder} and adds a backend-specific setting, a {@link Store},
 * the way a real backend would add a data source.
 */
final class InMemoryConfigurationFactory implements ConfigurationFactory {

  private final Store store;
  private final Application application;
  private final Environment environment;

  private InMemoryConfigurationFactory(final Store store,
                                       final Application application,
                                       final Environment environment) {
    this.store = store;
    this.application = application;
    this.environment = environment;
  }

  static Builder builder() {
    return new Builder();
  }

  @Override
  public Configuration configuration() {
    return Configuration.builder().with(store.values(application, environment)).build();
  }

  /**
   * The backend's "data source": values per application and environment. An application and
   * environment with nothing stored have no keys.
   */
  static final class Store {

    private final Map<Application, Map<Environment, Map<String, String>>> data = new HashMap<>();

    Store put(final Application application, final Environment environment, final Map<String, String> values) {
      data.computeIfAbsent(application, a -> new HashMap<>()).put(environment, Map.copyOf(values));
      return this;
    }

    Map<String, String> values(final Application application, final Environment environment) {
      return data.getOrDefault(application, Map.of()).getOrDefault(environment, Map.of());
    }
  }

  static final class Builder extends ConfigurationFactory.Builder<Builder> {

    private Store store;

    private Builder() {
    }

    Builder with(final Store store) {
      this.store = Objects.requireNonNull(store, "store must not be null");
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    @Override
    protected ConfigurationFactory build(final Application application, final Environment environment) {
      if (store == null) {
        throw new IllegalStateException("no store set");
      }
      return new InMemoryConfigurationFactory(store, application, environment);
    }
  }
}
