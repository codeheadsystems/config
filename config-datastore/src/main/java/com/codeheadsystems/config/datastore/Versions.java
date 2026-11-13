package com.codeheadsystems.config.datastore;

/**
 * Special version numbers, and the version check every backend makes before a write.
 *
 * <p>A set's version is at least 1 while it exists, and only ever increases for a {@link SetId}:
 * a set that is deleted and then created again continues from its old version, so a reader that
 * remembers a version can never mistake a new set for the one it saw.
 */
public final class Versions {

  /**
   * The version of a set that does not exist. As an expected version, it means "only if the set
   * does not exist".
   */
  public static final long NONE = 0L;

  /**
   * As an expected version: write whatever the current version is.
   */
  public static final long ANY = -1L;

  private Versions() {
  }

  /**
   * Checks that an expected version is {@link #ANY}, {@link #NONE}, or a real version.
   *
   * @param expectedVersion the expected version.
   * @return the expected version.
   * @throws IllegalArgumentException if it is less than {@link #ANY}.
   */
  public static long validateExpected(final long expectedVersion) {
    if (expectedVersion < ANY) {
      throw new IllegalArgumentException("invalid expected version: " + expectedVersion);
    }
    return expectedVersion;
  }

  /**
   * Whether a write expecting one version may proceed against the actual version.
   *
   * @param expectedVersion the version the writer expects, or {@link #ANY}.
   * @param actualVersion   the set's current version, or {@link #NONE} if it does not exist.
   * @return true if the write may proceed.
   */
  public static boolean matches(final long expectedVersion, final long actualVersion) {
    return validateExpected(expectedVersion) == ANY || expectedVersion == actualVersion;
  }
}
