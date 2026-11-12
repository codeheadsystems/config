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
 * namespace, which is prefixed to every key before the lookup.
 */
public final class Configuration {

  private static final Logger logger = LoggerFactory.getLogger(Configuration.class);

  private final Function<Key, Optional<Value>> function;
  private final Key namespace; // null for the root view

  private Configuration(final Function<Key, Optional<Value>> function, final Key namespace) {
    this.function = Objects.requireNonNull(function, "function must not be null");
    this.namespace = namespace;
  }

  public static Configuration from(final Function<Key, Optional<Value>> function) {
    return new Configuration(function, null);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Optional<Value> find(final Key key) {
    final Key fullKey = resolve(key);
    final Optional<Value> result = function.apply(fullKey);
    logger.trace("key found-> {}:{}", fullKey.name(), result.isPresent());
    return result;
  }

  public Optional<Value> find(final String key) {
    return find(Key.from(key));
  }

  // Reports the full key, so a missing key in a namespace is 'database.url', not 'url'.
  public Value get(final Key key) {
    final Key fullKey = resolve(key);
    return find(key).orElseThrow(() -> new NoSuchElementException("Configuration not found: " + fullKey.name()));
  }

  public Value get(final String key) {
    return get(Key.from(key));
  }

  public Configuration with(final String key) {
    return with(Key.from(key));
  }

  public Configuration with(final Key key) {
    Objects.requireNonNull(key, "namespace must not be null");
    return new Configuration(function, resolve(key));
  }

  public void with(final Key key, final Consumer<Configuration> consumer) {
    Objects.requireNonNull(consumer, "consumer must not be null");
    consumer.accept(with(key));
  }

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

    public Builder with(final Function<Key, Optional<Value>> function) {
      sources.add(Objects.requireNonNull(function, "function must not be null"));
      return this;
    }

    public Builder with(final Configuration configuration) {
      Objects.requireNonNull(configuration, "configuration must not be null");
      return with(configuration::find);
    }

    public Builder withNullFunction() {
      return with(key -> Optional.empty());
    }

    public Builder with(final Properties properties) {
      Objects.requireNonNull(properties, "properties must not be null");
      return with(key -> Value.from(properties.getProperty(key.name())));
    }

    public Builder with(final Map<String, String> map) {
      Objects.requireNonNull(map, "map must not be null");
      return with(key -> Value.from(map.get(key.name())));
    }

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
