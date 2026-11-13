package com.codeheadsystems.config.datastore;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The outcome of {@link ConfigDatastore#validate(Access)}: each check the backend made, in order.
 *
 * @param datastore a description of the datastore checked, such as {@code s3://config/config/}.
 * @param access    the access that was checked for.
 * @param checks    the checks, in the order they were made.
 */
public record ValidationResult(String datastore, Access access, List<Check> checks) {

  /**
   * Validates and copies the result.
   *
   * @param datastore the datastore checked.
   * @param access    the access checked for.
   * @param checks    the checks.
   */
  public ValidationResult {
    Objects.requireNonNull(datastore, "datastore must not be null");
    Objects.requireNonNull(access, "access must not be null");
    checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
  }

  /**
   * Whether every check passed or only warned.
   *
   * @return true if no check failed.
   */
  public boolean ok() {
    return failures().isEmpty();
  }

  /**
   * The checks that failed.
   *
   * @return the failures, in order.
   */
  public List<Check> failures() {
    return checks.stream().filter(check -> check.status() == Status.FAILED).toList();
  }

  /**
   * Throws if any check failed, listing every failure.
   *
   * @return this result.
   * @throws ConfigDatastoreException if any check failed.
   */
  public ValidationResult requireOk() {
    if (!ok()) {
      throw new ConfigDatastoreException("Datastore " + datastore + " is not usable for " + access + ": "
        + failures().stream().map(check -> check.name() + " (" + check.detail() + ")")
        .collect(Collectors.joining("; ")));
    }
    return this;
  }

  /** How a check came out. */
  public enum Status {
    /** The datastore meets this requirement. */
    PASSED,
    /** Not a requirement, but worth knowing, such as a probe object that could not be cleaned up. */
    WARNING,
    /** The datastore does not meet this requirement; using it for this access will fail. */
    FAILED
  }

  /**
   * One check.
   *
   * @param name   what was checked, such as {@code s3:PutObject}.
   * @param status how it came out.
   * @param detail what was found, or how to fix a failure.
   */
  public record Check(String name, Status status, String detail) {

    /**
     * Validates the check.
     *
     * @param name   what was checked.
     * @param status how it came out.
     * @param detail what was found.
     */
    public Check {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(status, "status must not be null");
      Objects.requireNonNull(detail, "detail must not be null");
    }

    /**
     * A passed check.
     *
     * @param name   what was checked.
     * @param detail what was found.
     * @return the check.
     */
    public static Check passed(final String name, final String detail) {
      return new Check(name, Status.PASSED, detail);
    }

    /**
     * A warning.
     *
     * @param name   what was checked.
     * @param detail what was found.
     * @return the check.
     */
    public static Check warning(final String name, final String detail) {
      return new Check(name, Status.WARNING, detail);
    }

    /**
     * A failed check.
     *
     * @param name   what was checked.
     * @param detail what was found, and how to fix it.
     * @return the check.
     */
    public static Check failed(final String name, final String detail) {
      return new Check(name, Status.FAILED, detail);
    }
  }
}
