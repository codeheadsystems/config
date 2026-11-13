package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.Objects;

/**
 * One change within an atomic write. A write's changes are applied in order.
 */
public sealed interface Change permits Change.Put, Change.Delete, Change.Clear {

  /**
   * Sets a key to a value.
   *
   * @param key   the key.
   * @param value the value.
   * @return the change.
   */
  static Change put(final Key key, final Value value) {
    return new Put(key, value);
  }

  /**
   * Removes a key. Removing a key the set does not have is not an error.
   *
   * @param key the key.
   * @return the change.
   */
  static Change delete(final Key key) {
    return new Delete(key);
  }

  /**
   * Removes every key. The set still exists afterwards, with no keys.
   *
   * @return the change.
   */
  static Change clear() {
    return new Clear();
  }

  /**
   * Sets a key to a value.
   *
   * @param key   the key.
   * @param value the value.
   */
  record Put(Key key, Value value) implements Change {

    /**
     * Validates the change.
     *
     * @param key   the key.
     * @param value the value.
     */
    public Put {
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  /**
   * Removes a key.
   *
   * @param key the key.
   */
  record Delete(Key key) implements Change {

    /**
     * Validates the change.
     *
     * @param key the key.
     */
    public Delete {
      Objects.requireNonNull(key, "key must not be null");
    }
  }

  /**
   * Removes every key.
   */
  record Clear() implements Change {
  }
}
