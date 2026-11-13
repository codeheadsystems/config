package com.codeheadsystems.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A read-only view of configuration values, backed by a lookup function. A view may carry a
 * namespace, which is prefixed to every key before the lookup:
 *
 * <pre>{@code
 * Configuration database = configuration.with("database");
 * database.get("url");   // looks up "database.url"
 * }</pre>
 *
 * <p>Lookups by {@code String} validate the key first (see {@link Key}), so a malformed key such as
 * {@code "a b"} throws {@link IllegalArgumentException} rather than returning empty.
 */
public final class Configuration {

  private static final Logger logger = LoggerFactory.getLogger(Configuration.class);

  private final Function<Key, Optional<Value>> function;
  private final Key namespace; // null for the root view

  private Configuration(final Function<Key, Optional<Value>> function, final Key namespace) {
    this.function = Objects.requireNonNull(function, "function must not be null");
    this.namespace = namespace;
  }

  /**
   * A configuration backed by a lookup function.
   *
   * @param function returns the value for a key, or empty if there is none.
   * @return the configuration.
   */
  public static Configuration from(final Function<Key, Optional<Value>> function) {
    return new Configuration(function, null);
  }

  /**
   * Starts building a configuration from one or more sources.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Looks up a value.
   *
   * @param key the key, relative to this view's namespace.
   * @return the value, or empty if there is none.
   */
  public Optional<Value> find(final Key key) {
    final Key fullKey = resolve(key);
    final Optional<Value> result = function.apply(fullKey);
    logger.trace("lookup {} found={}", fullKey.name(), result.isPresent());
    return result;
  }

  /**
   * Looks up a value.
   *
   * @param key the key, relative to this view's namespace.
   * @return the value, or empty if there is none.
   * @throws IllegalArgumentException if the key is not valid.
   */
  public Optional<Value> find(final String key) {
    return find(Key.from(key));
  }

  /**
   * Looks up a value that must exist. The error names the full key, so a missing key in the
   * {@code database} namespace is reported as {@code database.url}, not {@code url}.
   *
   * @param key the key, relative to this view's namespace.
   * @return the value.
   * @throws NoSuchElementException if there is no value.
   */
  public Value get(final Key key) {
    final Key fullKey = resolve(key);
    return find(key).orElseThrow(() -> new NoSuchElementException("Configuration not found: " + fullKey.name()));
  }

  /**
   * Looks up a value that must exist.
   *
   * @param key the key, relative to this view's namespace.
   * @return the value.
   * @throws NoSuchElementException   if there is no value.
   * @throws IllegalArgumentException if the key is not valid.
   */
  public Value get(final String key) {
    return get(Key.from(key));
  }

  /**
   * A view of the keys under a namespace: {@code with("database").get("url")} reads
   * {@code database.url}. Namespaces nest.
   *
   * @param key the namespace, relative to this view's namespace.
   * @return the namespaced view; this view is unchanged.
   */
  public Configuration with(final String key) {
    return with(Key.from(key));
  }

  /**
   * A view of the keys under a namespace.
   *
   * @param key the namespace, relative to this view's namespace.
   * @return the namespaced view; this view is unchanged.
   */
  public Configuration with(final Key key) {
    Objects.requireNonNull(key, "namespace must not be null");
    return new Configuration(function, resolve(key));
  }

  /**
   * Runs a block with the view of the keys under a namespace, without a local variable.
   *
   * @param key      the namespace, relative to this view's namespace.
   * @param consumer the block; called once.
   */
  public void with(final Key key, final Consumer<Configuration> consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    consumer.accept(with(key));
  }

  /**
   * Runs a block with the view of the keys under a namespace, without a local variable.
   *
   * @param key      the namespace, relative to this view's namespace.
   * @param consumer the block; called once.
   */
  public void with(final String key, final Consumer<Configuration> consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    consumer.accept(with(key));
  }

  private Key resolve(final Key key) {
    Objects.requireNonNull(key, "key must not be null");
    return namespace == null ? key : namespace.and(key);
  }

  /**
   * Builds a configuration from one or more sources. Each {@code with(...)} call adds a source;
   * a lookup checks the sources in the order they were added and returns the first value found,
   * so later sources act as fallbacks for earlier ones.
   */
  public static final class Builder {

    private final List<Function<Key, Optional<Value>>> sources = new ArrayList<>();

    private Builder() {
    }

    /**
     * Adds a lookup function as a source.
     *
     * @param function returns the value for a key, or empty if there is none.
     * @return this builder.
     */
    public Builder with(final Function<Key, Optional<Value>> function) {
      sources.add(Objects.requireNonNull(function, "function must not be null"));
      return this;
    }

    /**
     * Adds a configuration as a source, including its namespace.
     *
     * @param configuration the configuration.
     * @return this builder.
     */
    public Builder with(final Configuration configuration) {
      Objects.requireNonNull(configuration, "configuration must not be null");
      return with(configuration::find);
    }

    /**
     * Adds a source with no keys.
     *
     * @return this builder.
     */
    public Builder withNullFunction() {
      return with(key -> Optional.empty());
    }

    /**
     * Adds properties as a source, including their defaults. The properties are read live, not
     * copied.
     *
     * @param properties the properties.
     * @return this builder.
     */
    public Builder with(final Properties properties) {
      Objects.requireNonNull(properties, "properties must not be null");
      return with(key -> Value.from(properties.getProperty(key.name())));
    }

    /**
     * Adds a map as a source. The map is read live, not copied.
     *
     * @param map the keys and values.
     * @return this builder.
     */
    public Builder with(final Map<String, String> map) {
      Objects.requireNonNull(map, "map must not be null");
      return with(key -> Value.from(map.get(key.name())));
    }

    /**
     * Builds the configuration. Later changes to this builder do not affect it.
     *
     * @return the configuration.
     * @throws IllegalStateException if no source was added.
     */
    public Configuration build() {
      if (sources.isEmpty()) {
        throw new IllegalStateException("no configuration source set");
      }
      if (sources.size() == 1) {
        return from(sources.getFirst());
      }
      // Copy, so later calls on this builder do not change a configuration already built.
      final List<Function<Key, Optional<Value>>> ordered = List.copyOf(sources);
      return from(key -> {
        for (final Function<Key, Optional<Value>> source : ordered) {
          final Optional<Value> value = source.apply(key);
          if (value.isPresent()) {
            return value;
          }
        }
        return Optional.empty();
      });
    }
  }
}
