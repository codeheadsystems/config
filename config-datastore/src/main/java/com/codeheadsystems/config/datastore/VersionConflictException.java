package com.codeheadsystems.config.datastore;

import java.io.Serial;

/**
 * A write expected a set to be at one version, but it was at another.
 */
public class VersionConflictException extends ConfigDatastoreException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient SetId id;
  private final long expectedVersion;
  private final long actualVersion;

  /**
   * Creates the exception.
   *
   * @param id              the set.
   * @param expectedVersion the version the writer expected.
   * @param actualVersion   the set's version, or {@link Versions#NONE} if it does not exist.
   */
  public VersionConflictException(final SetId id, final long expectedVersion, final long actualVersion) {
    super("Version conflict on " + id + ": expected " + expectedVersion + ", actual " + actualVersion);
    this.id = id;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }

  /**
   * The set.
   *
   * @return the set.
   */
  public SetId id() {
    return id;
  }

  /**
   * The version the writer expected.
   *
   * @return the expected version.
   */
  public long expectedVersion() {
    return expectedVersion;
  }

  /**
   * The set's version when the write was refused.
   *
   * @return the actual version, or {@link Versions#NONE} if the set does not exist.
   */
  public long actualVersion() {
    return actualVersion;
  }
}
