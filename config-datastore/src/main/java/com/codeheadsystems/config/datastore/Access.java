package com.codeheadsystems.config.datastore;

/**
 * What a caller needs to do with a datastore, for {@link ConfigDatastore#validate(Access)}.
 */
public enum Access {

  /** Read sets and listings only, as config-library does. */
  READ,

  /** Read and write sets, as config-server does. */
  READ_WRITE
}
