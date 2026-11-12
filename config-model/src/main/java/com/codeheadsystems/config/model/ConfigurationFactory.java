package com.codeheadsystems.config.model;

import java.util.Objects;

/**
 * Produces the {@link Configuration} for the running process. A factory is bound to one
 * {@link Application}, the service that is running, and one {@link Environment}, where it is
 * running (such as {@code test} or {@code prod}). A service reads only its own configuration set,
 * so a factory never serves another application or environment.
 *
 * <p>Each datastore backend provides an implementation, along with a {@link Builder} subclass that
 * collects whatever else the backend needs, such as a data source.
 *
 * <pre>{@code
 * ConfigurationFactory factory = PostgresConfigurationFactory.builder()
 *     .with(Application.from("billing"))
 *     .with(Environment.from("prod"))
 *     .with(dataSource)
 *     .build();
 * Configuration configuration = factory.build();
 * }</pre>
 */
public interface ConfigurationFactory {

  /**
   * Produces the configuration for this factory's application and environment.
   *
   * @return the configuration.
   */
  Configuration build();

  /**
   * Base builder for a {@link ConfigurationFactory}. It holds the application and environment;
   * backends extend it to add their own settings and implement {@link #build(Application, Environment)}.
   *
   * @param <B> the concrete builder type, so {@code with(...)} calls chain with the subclass's own methods.
   */
  abstract class Builder<B extends Builder<B>> {

    private Application application;
    private Environment environment;

    protected Builder() {
    }

    public B with(final Application application) {
      this.application = Objects.requireNonNull(application, "application must not be null");
      return self();
    }

    public B with(final Environment environment) {
      this.environment = Objects.requireNonNull(environment, "environment must not be null");
      return self();
    }

    /**
     * Checks that the application and environment are set, then creates the factory.
     *
     * @return the factory.
     * @throws IllegalStateException if the application or environment is not set.
     */
    public final ConfigurationFactory build() {
      if (application == null) {
        throw new IllegalStateException("no application set");
      }
      if (environment == null) {
        throw new IllegalStateException("no environment set");
      }
      return build(application, environment);
    }

    /**
     * Returns this builder as its concrete type.
     *
     * @return {@code this}.
     */
    protected abstract B self();

    /**
     * Creates the backend's factory. Called by {@link #build()} once the application and
     * environment are known to be set; a backend validates its own settings here.
     *
     * @param application the application, never null.
     * @param environment the environment, never null.
     * @return the factory.
     */
    protected abstract ConfigurationFactory build(Application application, Environment environment);
  }
}
