package com.codeheadsystems.config.datastore.memory;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.Changes;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetSize;
import com.codeheadsystems.config.datastore.SetTooLargeException;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link ConfigDatastore} held in memory: for tests, and as the reference implementation of the
 * datastore contract. Nothing is persisted. Thread-safe.
 */
public final class InMemoryConfigDatastore implements ConfigDatastore {

  /**
   * The size limit used when none is given: 1 MiB.
   */
  public static final long DEFAULT_MAX_SET_BYTES = 1024L * 1024L;

  private final long maxSetBytes;
  // Deleted sets stay here as tombstones (values null) so their version is never reused.
  private final Map<SetId, Stored> sets = new HashMap<>();

  /**
   * Creates an empty datastore with the default size limit.
   */
  public InMemoryConfigDatastore() {
    this(DEFAULT_MAX_SET_BYTES);
  }

  /**
   * Creates an empty datastore.
   *
   * @param maxSetBytes the largest set it stores, at least 1.
   */
  public InMemoryConfigDatastore(final long maxSetBytes) {
    if (maxSetBytes < 1) {
      throw new IllegalArgumentException("maxSetBytes must be at least 1: " + maxSetBytes);
    }
    this.maxSetBytes = maxSetBytes;
  }

  @Override
  public synchronized Optional<Snapshot> load(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    final Stored stored = sets.get(id);
    if (stored == null || stored.deleted()) {
      return Optional.empty();
    }
    return Optional.of(new Snapshot(id, stored.values(), stored.version()));
  }

  @Override
  public synchronized long version(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return currentVersion(sets.get(id));
  }

  @Override
  public synchronized Set<Application> applications() {
    final Set<Application> result = new HashSet<>();
    live().forEach(id -> result.add(id.application()));
    return Set.copyOf(result);
  }

  @Override
  public synchronized Set<Environment> environments(final Application application) {
    Objects.requireNonNull(application, "application must not be null");
    final Set<Environment> result = new HashSet<>();
    live().filter(id -> id.application().equals(application)).forEach(id -> result.add(id.environment()));
    return Set.copyOf(result);
  }

  @Override
  public synchronized Set<SetType> types(final Application application, final Environment environment) {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    final Set<SetType> result = EnumSet.noneOf(SetType.class);
    live()
      .filter(id -> id.application().equals(application) && id.environment().equals(environment))
      .forEach(id -> result.add(id.type()));
    return Set.copyOf(result);
  }

  @Override
  public synchronized long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    Objects.requireNonNull(id, "id must not be null");
    final List<Change> validated = Changes.validate(changes);
    final Stored stored = sets.get(id);
    final long current = currentVersion(stored);
    if (!Versions.matches(expectedVersion, current)) {
      throw new VersionConflictException(id, expectedVersion, current);
    }
    final Map<Key, Value> before = stored == null || stored.deleted() ? Map.of() : stored.values();
    final Map<Key, Value> after = Changes.apply(before, validated);
    final long bytes = SetSize.bytes(after);
    if (bytes > maxSetBytes) {
      throw new SetTooLargeException(id, bytes, maxSetBytes);
    }
    // A tombstone keeps its last version, so a recreated set continues past it.
    final long next = (stored == null ? Versions.NONE : stored.version()) + 1;
    sets.put(id, new Stored(Map.copyOf(after), next));
    return next;
  }

  @Override
  public synchronized boolean delete(final SetId id, final long expectedVersion) {
    Objects.requireNonNull(id, "id must not be null");
    final Stored stored = sets.get(id);
    final long current = currentVersion(stored);
    if (!Versions.matches(expectedVersion, current)) {
      throw new VersionConflictException(id, expectedVersion, current);
    }
    if (current == Versions.NONE) {
      return false;
    }
    sets.put(id, new Stored(null, stored.version()));
    return true;
  }

  @Override
  public ValidationResult validate(final Access access) {
    Objects.requireNonNull(access, "access must not be null");
    return new ValidationResult("memory", access,
      List.of(ValidationResult.Check.passed("store", "in memory; always reachable and writable")));
  }

  @Override
  public long maxSetBytes() {
    return maxSetBytes;
  }

  private Stream<SetId> live() {
    return sets.entrySet().stream().filter(entry -> !entry.getValue().deleted()).map(Map.Entry::getKey);
  }

  private static long currentVersion(final Stored stored) {
    return stored == null || stored.deleted() ? Versions.NONE : stored.version();
  }

  /** A stored set; {@code values} is null for a deleted set. */
  private record Stored(Map<Key, Value> values, long version) {
    boolean deleted() {
      return values == null;
    }
  }
}
