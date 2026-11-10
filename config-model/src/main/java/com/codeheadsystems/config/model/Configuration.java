package com.codeheadsystems.config.model;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public interface Configuration {

  Optional<Value> find(String name);

  default Value get(String name) {
    return find(name).orElseThrow(() -> new NoSuchElementException("Configuration not found: " + name));
  }

  default Configuration with(String namespace) {
    return with(Namespace.from(namespace));
  }

  default Configuration with(Namespace namespace) {
    return new ConfigurationNamespace(this, namespace);
  }

  record ConfigurationNamespace(Configuration configuration, Namespace namespace) implements Configuration {

    public ConfigurationNamespace {
      Objects.requireNonNull(configuration, "configuration must not be null");
      Objects.requireNonNull(namespace, "namespace must not be null");
    }

    @Override
    public Optional<Value> find(final String name) {
      return configuration.find(namespace.name() + Namespace.SEPARATOR + name);
    }

    @Override
    public Configuration with(final Namespace namespace) {
      return new ConfigurationNamespace(configuration, this.namespace.and(namespace));
    }
  }
}
