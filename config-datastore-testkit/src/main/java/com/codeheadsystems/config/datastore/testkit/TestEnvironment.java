package com.codeheadsystems.config.datastore.testkit;

import org.junit.jupiter.api.Assumptions;

/**
 * Decides what a backend's tests do when something they need, such as Docker, is missing: skip
 * on a developer machine, but fail on CI, so a CI run can never pass green with a backend's tests
 * silently skipped.
 */
public final class TestEnvironment {

  private TestEnvironment() {
  }

  /**
   * Whether the tests are running on CI: the {@code CI} environment variable is set to anything
   * but {@code false}. GitHub Actions and most CI services set it to {@code true}.
   *
   * @return true on CI.
   */
  public static boolean onCi() {
    final String ci = System.getenv("CI");
    return ci != null && !ci.isBlank() && !ci.equalsIgnoreCase("false");
  }

  /**
   * Skips the calling tests if a requirement is missing, or fails them on CI.
   *
   * @param available whether the requirement is met.
   * @param what      what is required, for the message, such as {@code "Docker"}.
   * @throws IllegalStateException on CI, if the requirement is missing.
   */
  public static void require(final boolean available, final String what) {
    require(available, what, onCi());
  }

  static void require(final boolean available, final String what, final boolean onCi) {
    if (available) {
      return;
    }
    if (onCi) {
      throw new IllegalStateException(what + " is required to run these tests on CI (CI is set)");
    }
    Assumptions.abort(what + " is not available; skipping (these tests fail instead when CI is set)");
  }
}
