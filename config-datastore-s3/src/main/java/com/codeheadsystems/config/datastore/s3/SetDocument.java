package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON body of a set object: {@code {"version": 3, "values": {"database.url": "..."}}}.
 * Tombstones use the same shape with no values.
 *
 * @param version the set's version.
 * @param values  the set's keys and values.
 */
record SetDocument(long version, Map<Key, Value> values) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  SetDocument {
    values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
  }

  byte[] toJson() {
    final Map<String, String> plain = new TreeMap<>(); // sorted, so equal sets serialise identically
    values.forEach((key, value) -> plain.put(key.name(), value.value()));
    return JSON.writeValueAsBytes(new Json(version, plain));
  }

  static SetDocument fromJson(final byte[] json, final String objectKey) {
    final Json parsed;
    try {
      parsed = JSON.readValue(json, Json.class);
    } catch (final JacksonException e) {
      throw new ConfigDatastoreException("Unreadable set object " + objectKey, e);
    }
    if (parsed.version() < 1) {
      throw new ConfigDatastoreException("Set object " + objectKey + " has invalid version " + parsed.version());
    }
    final Map<Key, Value> values = new HashMap<>();
    if (parsed.values() != null) {
      parsed.values().forEach((key, value) -> {
        if (value == null) {
          throw new ConfigDatastoreException("Set object " + objectKey + " has a null value for " + key);
        }
        values.put(Key.from(key), new Value(value));
      });
    }
    return new SetDocument(parsed.version(), values);
  }

  /** The wire form. */
  record Json(long version, Map<String, String> values) {
  }
}
