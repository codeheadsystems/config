package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helpers for backends that store a set as one document: validate a write's changes, then apply
 * them to the current values to get the new ones. Using these keeps every backend's behaviour the
 * same.
 */
public final class Changes {

  private Changes() {
  }

  /**
   * Checks that a write's changes are usable.
   *
   * @param changes the changes.
   * @return the changes, copied.
   * @throws NullPointerException     if the list or any change is null.
   * @throws IllegalArgumentException if the list is empty.
   */
  public static List<Change> validate(final List<Change> changes) {
    Objects.requireNonNull(changes, "changes must not be null");
    final List<Change> copy = List.copyOf(changes); // rejects null elements
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("changes must not be empty");
    }
    return copy;
  }

  /**
   * Applies changes, in order, to a set's current values.
   *
   * @param current the current values; not modified.
   * @param changes the changes.
   * @return the new values, unmodifiable.
   */
  public static Map<Key, Value> apply(final Map<Key, Value> current, final List<Change> changes) {
    Objects.requireNonNull(current, "current must not be null");
    final Map<Key, Value> result = new HashMap<>(current);
    for (final Change change : validate(changes)) {
      switch (change) {
        case Change.Put put -> result.put(put.key(), put.value());
        case Change.Delete delete -> result.remove(delete.key());
        case Change.Clear clear -> result.clear();
      }
    }
    return Collections.unmodifiableMap(result);
  }
}
