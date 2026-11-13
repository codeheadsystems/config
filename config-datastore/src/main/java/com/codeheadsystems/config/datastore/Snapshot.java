package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable copy of one set at one version.
 *
 * @param id      the set.
 * @param values  the set's keys and values; copied, so later changes to the argument have no effect.
 * @param version the set's version, at least 1.
 */
public record Snapshot(SetId id, Map<Key, Value> values, long version) {

  /**
   * Validates and copies the snapshot.
   *
   * @param id      the set.
   * @param values  the set's keys and values.
   * @param version the set's version.
   */
  public Snapshot {
    Objects.requireNonNull(id, "id must not be null");
    values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    if (version < 1) {
      throw new IllegalArgumentException("version must be at least 1: " + version);
    }
  }

  /**
   * Looks up one value.
   *
   * @param key the key.
   * @return the value, or empty if the set does not have the key.
   */
  public Optional<Value> find(final Key key) {
    return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key must not be null")));
  }

  /**
   * The snapshot as a {@link Configuration}.
   *
   * @return a configuration that reads this snapshot.
   */
  public Configuration toConfiguration() {
    return Configuration.from(this::find);
  }
}
