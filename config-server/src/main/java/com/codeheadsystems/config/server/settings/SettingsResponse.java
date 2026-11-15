package com.codeheadsystems.config.server.settings;

import com.codeheadsystems.config.datastore.Snapshot;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code Settings} schema: a whole settings set.
 *
 * @param application the application.
 * @param environment the environment.
 * @param version     the set's version.
 * @param values      the keys and values, sorted by key.
 */
public record SettingsResponse(String application, String environment, long version, Map<String, String> values) {

  static SettingsResponse of(final Snapshot snapshot) {
    final Map<String, String> values = new TreeMap<>();
    snapshot.values().forEach((key, value) -> values.put(key.name(), value.value()));
    return new SettingsResponse(snapshot.id().application().value(), snapshot.id().environment().value(),
        snapshot.version(), values);
  }
}
