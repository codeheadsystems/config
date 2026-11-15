package com.codeheadsystems.config.server;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastoreProvider;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.memory.InMemoryConfigDatastore;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory datastore for tests whose reads can be made to fail: {@link Mode#UNREACHABLE}
 * throws what a backend throws when it cannot reach its store, {@link Mode#BROKEN} throws an
 * {@link Error}, which nothing should handle as a datastore failure. Writes always work, so tests
 * can set data up. Registered as {@value Provider#NAME} through {@code META-INF/services}.
 */
public final class FlakyDatastore implements ConfigDatastore {

  /** How reads behave. */
  public enum Mode { WORKING, UNREACHABLE, BROKEN }

  /** Shared by every instance: tests switch it, the server's datastore obeys it. */
  public static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.WORKING);

  private final InMemoryConfigDatastore store = new InMemoryConfigDatastore();

  private void read() {
    switch (MODE.get()) {
      case UNREACHABLE -> throw new ConfigDatastoreException("connection refused");
      case BROKEN -> throw new AssertionError("a bug, not an outage");
      case WORKING -> {
        // read normally
      }
    }
  }

  @Override
  public Optional<Snapshot> load(final SetId id) {
    read();
    return store.load(id);
  }

  @Override
  public long version(final SetId id) {
    read();
    return store.version(id);
  }

  @Override
  public Set<Application> applications() {
    read();
    return store.applications();
  }

  @Override
  public Set<Environment> environments(final Application application) {
    read();
    return store.environments(application);
  }

  @Override
  public Set<SetType> types(final Application application, final Environment environment) {
    read();
    return store.types(application, environment);
  }

  @Override
  public long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    return store.apply(id, expectedVersion, changes);
  }

  @Override
  public boolean delete(final SetId id, final long expectedVersion) {
    return store.delete(id, expectedVersion);
  }

  @Override
  public long maxSetBytes() {
    return store.maxSetBytes();
  }

  @Override
  public ValidationResult validate(final Access access) {
    return store.validate(access);
  }

  /** Provides {@link FlakyDatastore} as {@value #NAME}. */
  public static final class Provider implements ConfigDatastoreProvider {

    /** The datastore's name in configuration. */
    public static final String NAME = "flaky";

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public ConfigDatastore create(final Configuration options) {
      return new FlakyDatastore();
    }
  }
}
