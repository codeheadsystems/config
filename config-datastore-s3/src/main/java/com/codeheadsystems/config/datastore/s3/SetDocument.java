package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of a set object.
 *
 * <p>A live set is JSON: {@code {"format":1,"version":3,"values":{"database.url":"..."}}}. Readers
 * ignore fields they do not know, so a later release can add fields within format 1; a change that
 * older readers cannot safely ignore must raise {@link #FORMAT}.
 *
 * <p>A deleted set is a marker: exactly {@link #MARKER_BYTES} bytes, the version as hexadecimal.
 * Every live document is longer than that, so a listing can tell markers from live sets by object
 * size alone, without reading them. The marker holds the version so its content, and so its ETag,
 * is different every time a set is deleted.
 *
 * @param version the set's version.
 * @param values  the set's keys and values.
 */
record SetDocument(long version, Map<Key, Value> values) {

  /** The format this release writes, and the newest it reads. */
  static final int FORMAT = 1;

  /** The size of a deleted-set marker. No live document is this short. */
  static final int MARKER_BYTES = 16;

  private static final JsonMapper JSON = JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build();

  SetDocument {
    values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
  }

  byte[] toJson() {
    final Map<String, String> plain = new TreeMap<>(); // sorted, so equal sets serialise identically
    values.forEach((key, value) -> plain.put(key.name(), value.value()));
    final byte[] json = JSON.writeValueAsBytes(new Json(FORMAT, version, plain));
    if (json.length <= MARKER_BYTES) {
      throw new IllegalStateException("a live document must be longer than a marker"); // cannot happen
    }
    return json;
  }

  static byte[] marker(final long version) {
    return String.format("%016x", version).getBytes(StandardCharsets.US_ASCII);
  }

  static SetDocument fromJson(final byte[] json, final String objectKey) {
    final Json parsed;
    try {
      parsed = JSON.readValue(json, Json.class);
    } catch (final JacksonException e) {
      throw new ConfigDatastoreException("Unreadable set object " + objectKey, e);
    }
    if (parsed == null) {
      throw new ConfigDatastoreException("Set object " + objectKey + " is empty JSON");
    }
    if (parsed.format() < 1) {
      throw new ConfigDatastoreException("Set object " + objectKey + " has no format number");
    }
    if (parsed.format() > FORMAT) {
      throw new ConfigDatastoreException("Set object " + objectKey + " uses format " + parsed.format()
        + ", newer than this release reads (" + FORMAT + "); upgrade config-datastore-s3");
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
        try {
          values.put(Key.from(key), new Value(value));
        } catch (final IllegalArgumentException e) {
          throw new ConfigDatastoreException("Set object " + objectKey + " has an invalid key '" + key + "'", e);
        }
      });
    }
    return new SetDocument(parsed.version(), values);
  }

  /** The wire form. */
  record Json(int format, long version, Map<String, String> values) {
  }
}
