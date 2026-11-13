package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * The size of a set, as every backend measures it against its {@link ConfigWriter#maxSetBytes()}:
 * the UTF-8 bytes of every key name plus every value. A backend's own storage overhead (field
 * names, versions, encoding) is not counted; the backend allows for it when choosing its limit.
 */
public final class SetSize {

  private SetSize() {
  }

  /**
   * Measures a set's values.
   *
   * @param values the keys and values.
   * @return the size in bytes.
   */
  public static long bytes(final Map<Key, Value> values) {
    Objects.requireNonNull(values, "values must not be null");
    long total = 0;
    for (final Map.Entry<Key, Value> entry : values.entrySet()) {
      total += utf8Length(entry.getKey().name()) + utf8Length(entry.getValue().value());
    }
    return total;
  }

  private static long utf8Length(final String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }
}
