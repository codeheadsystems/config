package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes sets to a datastore. Backends implement {@link #apply}, {@link #delete} and
 * {@link #maxSetBytes}; the other methods are built on them.
 */
public interface ConfigWriter {

  /**
   * Applies changes to a set atomically, in order: either every change is made or none is.
   * Creates the set if it does not exist. Every successful call increases the set's version, even
   * if its changes leave the values as they were.
   *
   * @param id              the set.
   * @param expectedVersion the set's current version, {@link Versions#NONE} to require that the set
   *                        does not exist, or {@link Versions#ANY} to skip the check.
   * @param changes         the changes; must not be empty.
   * @return the set's new version.
   * @throws VersionConflictException if the set's version does not match {@code expectedVersion}.
   * @throws SetTooLargeException     if the resulting set would be larger than {@link #maxSetBytes()}.
   * @throws IllegalArgumentException if {@code changes} is empty or the expected version is invalid.
   */
  long apply(SetId id, long expectedVersion, List<Change> changes);

  /**
   * Deletes a whole set. Its version is kept, so a set created again later continues from it.
   *
   * @param id              the set.
   * @param expectedVersion the set's current version, or {@link Versions#ANY} to skip the check.
   * @return true if the set existed and was deleted; false if it did not exist.
   * @throws VersionConflictException if the set's version does not match {@code expectedVersion}.
   */
  boolean delete(SetId id, long expectedVersion);

  /**
   * The largest set this backend stores, measured by {@link SetSize#bytes}. Each backend chooses
   * its own limit from what its storage allows.
   *
   * @return the limit in bytes.
   */
  long maxSetBytes();

  /**
   * Sets one key.
   *
   * @param id              the set.
   * @param expectedVersion as for {@link #apply}.
   * @param key             the key.
   * @param value           the value.
   * @return the set's new version.
   */
  default long put(final SetId id, final long expectedVersion, final Key key, final Value value) {
    return apply(id, expectedVersion, List.of(Change.put(key, value)));
  }

  /**
   * Removes one key. Removing a key the set does not have still counts as a write.
   *
   * @param id              the set.
   * @param expectedVersion as for {@link #apply}.
   * @param key             the key.
   * @return the set's new version.
   */
  default long remove(final SetId id, final long expectedVersion, final Key key) {
    return apply(id, expectedVersion, List.of(Change.delete(key)));
  }

  /**
   * Replaces a set's values with exactly these, atomically.
   *
   * @param id              the set.
   * @param expectedVersion as for {@link #apply}.
   * @param values          the new values; may be empty, which leaves an existing set with no keys.
   * @return the set's new version.
   */
  default long replace(final SetId id, final long expectedVersion, final Map<Key, Value> values) {
    Objects.requireNonNull(values, "values must not be null");
    final List<Change> changes = new ArrayList<>(values.size() + 1);
    changes.add(Change.clear());
    values.forEach((key, value) -> changes.add(Change.put(key, value)));
    return apply(id, expectedVersion, changes);
  }
}
