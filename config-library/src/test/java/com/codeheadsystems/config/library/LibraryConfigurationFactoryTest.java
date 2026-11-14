package com.codeheadsystems.config.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigReader;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.datastore.memory.InMemoryConfigDatastore;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.ConfigurationFactory;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LibraryConfigurationFactoryTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private static final SetId SETTINGS = SetId.settings(BILLING, PROD);

  private InMemoryConfigDatastore defaultsStore;
  private InMemoryConfigDatastore updatesStore;
  private FlakyReader updates;
  private LibraryConfigurationFactory factory;

  @BeforeEach
  void setUp() {
    defaultsStore = new InMemoryConfigDatastore();
    updatesStore = new InMemoryConfigDatastore();
    updates = new FlakyReader(updatesStore);
    defaultsStore.replace(SETTINGS, Versions.ANY, values(Map.of("timeout", "30s", "database.url", "jdbc:defaults")));
  }

  @AfterEach
  void tearDown() {
    if (factory != null) {
      factory.close();
    }
  }

  private LibraryConfigurationFactory.Builder builder() {
    return LibraryConfigurationFactory.builder().with(BILLING).with(PROD).refreshInterval(Duration.ZERO);
  }

  private static Map<Key, Value> values(final Map<String, String> values) {
    final Map<Key, Value> result = new HashMap<>();
    values.forEach((key, value) -> result.put(Key.from(key), new Value(value)));
    return result;
  }

  private Optional<String> lookup(final String key) {
    return factory.configuration().find(key).map(Value::value);
  }

  // Layering

  @Test
  void updatesOverrideDefaultsAndMissingKeysFallBack() {
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().defaults(defaultsStore).updates(updates).build();

    assertThat(lookup("timeout")).contains("5s");
    assertThat(lookup("database.url")).contains("jdbc:defaults");
    assertThat(lookup("missing")).isEmpty();
  }

  @Test
  void defaultsAlone() {
    factory = builder().defaults(defaultsStore).build();

    assertThat(lookup("timeout")).contains("30s");
    assertThat(factory.updatesLoaded()).isFalse();
    assertThat(factory.refresh()).isFalse();
  }

  @Test
  void updatesAlone() {
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().updates(updates).build();

    assertThat(lookup("timeout")).contains("5s");
    assertThat(lookup("database.url")).isEmpty();
  }

  @Test
  void onlyTheSettingsSetOfItsOwnApplicationAndEnvironmentIsRead() {
    updatesStore.put(SetId.metadata(BILLING, PROD), Versions.ANY, Key.from("owner"), new Value("team"));
    updatesStore.put(SetId.settings(BILLING, Environment.from("dev")), Versions.ANY, Key.from("owner"), new Value("dev"));
    updatesStore.put(SetId.settings(Application.from("shipping"), PROD), Versions.ANY, Key.from("owner"), new Value("x"));
    factory = builder().defaults(defaultsStore).updates(updates).build();

    assertThat(lookup("owner")).isEmpty();
  }

  @Test
  void missingSetsAreEmpty() {
    factory = builder().defaults(new InMemoryConfigDatastore()).updates(updates).build();

    assertThat(lookup("timeout")).isEmpty();
    assertThat(factory.updatesLoaded()).isTrue();
    assertThat(factory.updatesVersion()).isEqualTo(Versions.NONE);
  }

  @Test
  void namespacesWork() {
    factory = builder().defaults(defaultsStore).build();

    assertThat(factory.configuration().with("database").get("url")).isEqualTo(new Value("jdbc:defaults"));
  }

  // Refresh

  @Test
  void refreshPicksUpAChangeInConfigurationsAlreadyHandedOut() {
    factory = builder().defaults(defaultsStore).updates(updates).build();
    final Configuration configuration = factory.configuration();
    final long version = updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));

    assertThat(configuration.find("timeout").map(Value::value)).contains("30s");
    assertThat(factory.refresh()).isTrue();
    assertThat(configuration.find("timeout").map(Value::value)).contains("5s");
    assertThat(factory.updatesVersion()).isEqualTo(version);
  }

  @Test
  void refreshWithoutAChangeChecksOnlyTheVersion() {
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().updates(updates).build();
    final int loads = updates.loads.get();

    assertThat(factory.refresh()).isFalse();
    assertThat(updates.loads.get()).isEqualTo(loads);
    assertThat(updates.versions.get()).isEqualTo(1);
  }

  @Test
  void defaultsAreReadOnce() {
    final FlakyReader defaults = new FlakyReader(defaultsStore);
    factory = builder().defaults(defaults).updates(updates).build();
    defaultsStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("1s"));
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("other"), new Value("x"));

    factory.refresh();

    assertThat(defaults.loads.get()).isEqualTo(1);
    assertThat(defaults.versions.get()).isZero();
    assertThat(lookup("timeout")).contains("30s");
  }

  @Test
  void deletingTheUpdatesSetFallsBackToDefaults() {
    final long version = updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().defaults(defaultsStore).updates(updates).build();

    updatesStore.delete(SETTINGS, version);

    assertThat(factory.refresh()).isTrue();
    assertThat(lookup("timeout")).contains("30s");
    assertThat(factory.updatesVersion()).isEqualTo(Versions.NONE);
  }

  @Test
  void backgroundThreadPollsForChanges() throws InterruptedException {
    factory = builder().defaults(defaultsStore).updates(updates).refreshInterval(Duration.ofMillis(10)).build();

    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));

    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!lookup("timeout").equals(Optional.of("5s")) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(lookup("timeout")).contains("5s");
  }

  @Test
  void closeStopsPolling() throws InterruptedException {
    factory = builder().updates(updates).refreshInterval(Duration.ofMillis(5)).build();
    factory.close();
    Thread.sleep(20); // let a check already running finish
    final int versions = updates.versions.get();

    Thread.sleep(50);

    assertThat(updates.versions.get()).isEqualTo(versions);
    factory.close(); // a second close does nothing
  }

  @Test
  void usableThroughTheInterfaceWithTryWithResources() {
    try (ConfigurationFactory closeable = builder().defaults(defaultsStore).build()) {
      assertThat(closeable.configuration().get("timeout")).isEqualTo(new Value("30s"));
    }
  }

  // Failures

  @Test
  void unreachableUpdatesAtStartupServesDefaultsAndRecovers() {
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    updates.failing.set(true);
    factory = builder().defaults(defaultsStore).updates(updates).build();

    assertThat(lookup("timeout")).contains("30s");
    assertThat(factory.updatesLoaded()).isFalse();
    assertThat(factory.refresh()).isFalse();

    updates.failing.set(false);

    assertThat(factory.refresh()).isTrue();
    assertThat(factory.updatesLoaded()).isTrue();
    assertThat(lookup("timeout")).contains("5s");
  }

  @Test
  void unreachableUpdatesAtStartupFailsWhenRequired() {
    updates.failing.set(true);

    assertThatThrownBy(() -> builder().defaults(defaultsStore).updates(updates).requireUpdatesAtStartup(true).build())
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("updates datastore");
  }

  @Test
  void requiredUpdatesThatAreReachableBuildNormally() {
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().defaults(defaultsStore).updates(updates).requireUpdatesAtStartup(true).build();

    assertThat(lookup("timeout")).contains("5s");
  }

  @Test
  void aFailedRefreshKeepsTheLastValues() {
    final long version = updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("5s"));
    factory = builder().defaults(defaultsStore).updates(updates).build();
    updates.failing.set(true);
    updatesStore.put(SETTINGS, Versions.ANY, Key.from("timeout"), new Value("1s"));

    assertThat(factory.refresh()).isFalse();

    assertThat(lookup("timeout")).contains("5s");
    assertThat(factory.updatesVersion()).isEqualTo(version);
  }

  @Test
  void unreadableDefaultsFailTheBuild() {
    final FlakyReader defaults = new FlakyReader(defaultsStore);
    defaults.failing.set(true);

    assertThatThrownBy(() -> builder().defaults(defaults).updates(updates).build())
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("defaults datastore");
  }

  // Builder

  @Test
  void aDatastoreIsRequired() {
    assertThatThrownBy(() -> builder().build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("no datastore set");
  }

  @Test
  void applicationAndEnvironmentAreRequired() {
    assertThatThrownBy(() -> LibraryConfigurationFactory.builder().with(PROD).defaults(defaultsStore).build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("no application set");
  }

  @Test
  void invalidRefreshIntervalsAreRejected() {
    final LibraryConfigurationFactory.Builder builder = builder();

    assertThatThrownBy(() -> builder.refreshInterval(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.refreshInterval(Duration.ofNanos(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.refreshInterval(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullDatastoresAreRejected() {
    assertThatThrownBy(() -> builder().defaults(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> builder().updates(null)).isInstanceOf(NullPointerException.class);
  }

  /** Delegates to a datastore, counting reads, and fails every read while {@link #failing} is set. */
  private static final class FlakyReader implements ConfigReader {

    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicInteger loads = new AtomicInteger();
    final AtomicInteger versions = new AtomicInteger();
    private final ConfigReader delegate;

    FlakyReader(final ConfigReader delegate) {
      this.delegate = delegate;
    }

    private void check() {
      if (failing.get()) {
        throw new IllegalStateException("datastore unreachable");
      }
    }

    @Override
    public Optional<Snapshot> load(final SetId id) {
      check();
      loads.incrementAndGet();
      return delegate.load(id);
    }

    @Override
    public long version(final SetId id) {
      check();
      versions.incrementAndGet();
      return delegate.version(id);
    }

    @Override
    public Set<Application> applications() {
      return delegate.applications();
    }

    @Override
    public Set<Environment> environments(final Application application) {
      return delegate.environments(application);
    }

    @Override
    public Set<SetType> types(final Application application, final Environment environment) {
      return delegate.types(application, environment);
    }
  }
}
