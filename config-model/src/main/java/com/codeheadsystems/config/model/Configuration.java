package com.codeheadsystems.config.model;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public interface Configuration {

  Optional<Value> find(Key key);

  default Optional<Value> find(String key) {
    return find(Key.from(key));
  }

  default Value get(Key key) {
    return find(key).orElseThrow(() -> new NoSuchElementException("Configuration not found: " + key.name()));
  }

  default Value get(String key) {
    return get(Key.from(key));
  }

  default Configuration withNamespace(String key) {
    return withNamespace(Key.from(key));
  }

  default Configuration withNamespace(Key namespace) {
    return new ConfigurationNamespace(this, namespace);
  }

  record ConfigurationNamespace(Configuration configuration, Key namespace) implements Configuration {

    public ConfigurationNamespace {
      Objects.requireNonNull(configuration, "configuration must not be null");
      Objects.requireNonNull(namespace, "namespace must not be null");
    }

    @Override
    public Optional<Value> find(final Key key) {
      return configuration.find(namespace.and(key));
    }

    // Delegates with the full key, so a missing key is reported as 'database.url', not 'url'.
    @Override
    public Value get(final Key key) {
      return configuration.get(namespace.and(key));
    }

    @Override
    public Configuration withNamespace(final Key key) {
      return new ConfigurationNamespace(configuration, namespace.and(key));
    }
  }
}
