package com.codeheadsystems.config.datastore;

import java.io.Serial;

/**
 * A write would have made a set larger than the backend's {@link ConfigWriter#maxSetBytes()}.
 * The set is unchanged.
 */
public class SetTooLargeException extends ConfigDatastoreException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient SetId id;
  private final long bytes;
  private final long maxBytes;

  /**
   * Creates the exception.
   *
   * @param id       the set.
   * @param bytes    the size the set would have had, measured by {@link SetSize#bytes}.
   * @param maxBytes the backend's limit.
   */
  public SetTooLargeException(final SetId id, final long bytes, final long maxBytes) {
    super("Set " + id + " would be " + bytes + " bytes; the limit is " + maxBytes);
    this.id = id;
    this.bytes = bytes;
    this.maxBytes = maxBytes;
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
   * The size the set would have had.
   *
   * @return the size in bytes.
   */
  public long bytes() {
    return bytes;
  }

  /**
   * The backend's limit.
   *
   * @return the limit in bytes.
   */
  public long maxBytes() {
    return maxBytes;
  }
}
