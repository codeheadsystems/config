package com.codeheadsystems.config.library;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigReader;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.ConfigurationFactory;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ConfigurationFactory} that reads the application's settings directly from datastores,
 * layered:
 *
 * <ol>
 *   <li><b>Updates</b>: a datastore, usually the live one, whose values take precedence. It is
 *       re-checked on an interval: a background thread asks for the set's
 *       {@linkplain ConfigReader#version(SetId) version} and reloads the set only when the version
 *       has changed.</li>
 *   <li><b>Defaults</b>: a datastore read once, when the factory is built, such as an export of the
 *       live datastore. A key the updates do not have falls back to it.</li>
 * </ol>
 *
 * <p>Either layer may be left out, but not both. Only the {@code SETTINGS} set for the factory's
 * application and environment is ever read. Lookups never touch a datastore: they read the
 * in-memory copies, so they are cheap and never fail because a datastore is unreachable.
 *
 * <pre>{@code
 * try (LibraryConfigurationFactory factory = LibraryConfigurationFactory.builder()
 *     .with(Application.from("billing"))
 *     .with(Environment.from("prod"))
 *     .defaults(exportDatastore)
 *     .updates(liveDatastore)
 *     .build()) {
 *   Configuration configuration = factory.configuration();
 *   configuration.get("database.url");
 * }
 * }</pre>
 *
 * <p>If the updates datastore cannot be read when the factory is built, the factory serves the
 * defaults alone and keeps trying on every refresh; {@link Builder#requireUpdatesAtStartup(boolean)}
 * makes it fail instead. A refresh that fails later keeps serving the last values read.
 *
 * <p>The factory does not own the datastores: closing it stops the refresh thread, and the caller
 * closes the datastores.
 */
public final class LibraryConfigurationFactory implements ConfigurationFactory {

  /** How often the updates datastore is checked when no interval is set. */
  public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);

  private static final Logger logger = LoggerFactory.getLogger(LibraryConfigurationFactory.class);

  /** The updates layer before any successful read: matches no real version, so a refresh always loads. */
  private static final Layer NOT_LOADED = new Layer(Map.of(), Long.MIN_VALUE);

  private final SetId id;
  private final ConfigReader updates; // null if there is no updates layer
  private final Configuration configuration;
  private final ScheduledExecutorService scheduler; // null if not polling
  private volatile Layer updatesLayer = NOT_LOADED;
  private boolean failing; // guarded by this; only for logging transitions

  private LibraryConfigurationFactory(final SetId id,
                                      final ConfigReader defaults,
                                      final ConfigReader updates,
                                      final Duration refreshInterval,
                                      final boolean requireUpdatesAtStartup) {
    this.id = id;
    this.updates = updates;
    final Map<Key, Value> defaultValues = defaults == null ? Map.of() : loadDefaults(defaults);
    if (updates != null) {
      if (requireUpdatesAtStartup) {
        updatesLayer = load(updates);
      } else {
        refresh();
      }
    }
    this.configuration = Configuration.builder()
      .with(key -> updatesLayer.find(key))
      .with(key -> Optional.ofNullable(defaultValues.get(key)))
      .build();
    this.scheduler = updates == null || refreshInterval.isZero() ? null : startPolling(refreshInterval);
  }

  /**
   * Starts building a factory.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The configuration. The same instance is returned every time, and it always reads the latest
   * values: a refresh is seen by configurations already handed out.
   *
   * @return the configuration.
   */
  @Override
  public Configuration configuration() {
    return configuration;
  }

  /**
   * Checks the updates datastore now, reloading the set if its version has changed. The background
   * thread calls this on every interval; call it directly to pick up a change sooner. Never throws
   * for a datastore failure: the failure is logged and the last values read are kept.
   *
   * @return true if new values were loaded.
   */
  public synchronized boolean refresh() {
    if (updates == null) {
      return false;
    }
    try {
      final Layer current = updatesLayer;
      if (current != NOT_LOADED && updates.version(id) == current.version()) {
        recovered();
        return false;
      }
      final Layer loaded = load(updates);
      updatesLayer = loaded;
      recovered();
      if (loaded.version() == current.version()) {
        return false;
      }
      logger.info("Loaded {} {} settings version {} ({} keys)",
        id.application().value(), id.environment().value(), loaded.version(), loaded.values().size());
      return true;
    } catch (final RuntimeException e) {
      if (!failing) {
        failing = true;
        logger.warn("Cannot read {} {} settings from the updates datastore; serving {}",
          id.application().value(), id.environment().value(),
          updatesLayer == NOT_LOADED ? "defaults only" : "version " + updatesLayer.version(), e);
      } else {
        logger.debug("Updates datastore still unreadable", e);
      }
      return false;
    }
  }

  /**
   * Whether the updates layer has been read at least once. False if there is no updates datastore,
   * or if it has been unreachable since startup, in which case only the defaults are served.
   *
   * @return true if the updates layer has been read.
   */
  public boolean updatesLoaded() {
    return updatesLayer != NOT_LOADED;
  }

  /**
   * The version of the updates set being served.
   *
   * @return the version; {@link Versions#NONE} if the set does not exist, or if the updates layer
   *     has not been read (see {@link #updatesLoaded()}).
   */
  public long updatesVersion() {
    final Layer layer = updatesLayer;
    return layer == NOT_LOADED ? Versions.NONE : layer.version();
  }

  /**
   * Stops the refresh thread. The configuration keeps serving the last values read. Does not close
   * the datastores. Calling it again does nothing.
   */
  @Override
  public void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private void recovered() {
    if (failing) {
      failing = false;
      logger.info("Updates datastore readable again for {} {}", id.application().value(), id.environment().value());
    }
  }

  private Map<Key, Value> loadDefaults(final ConfigReader defaults) {
    try {
      return defaults.load(id).map(Snapshot::values).orElse(Map.of());
    } catch (final RuntimeException e) {
      throw new ConfigDatastoreException("Cannot read " + id.application().value() + " "
        + id.environment().value() + " settings from the defaults datastore", e);
    }
  }

  private Layer load(final ConfigReader reader) {
    try {
      return reader.load(id)
        .map(snapshot -> new Layer(snapshot.values(), snapshot.version()))
        .orElseGet(() -> new Layer(Map.of(), Versions.NONE));
    } catch (final ConfigDatastoreException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new ConfigDatastoreException("Cannot read " + id.application().value() + " "
        + id.environment().value() + " settings from the updates datastore", e);
    }
  }

  private ScheduledExecutorService startPolling(final Duration interval) {
    final String name = "config-refresh-" + id.application().value() + "-" + id.environment().value();
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    });
    final long millis = interval.toMillis();
    // refresh() never throws for a datastore failure; an Error still ends polling, as it should.
    executor.scheduleWithFixedDelay(this::refresh, millis, millis, TimeUnit.MILLISECONDS);
    return executor;
  }

  /** One read of the updates set. */
  private record Layer(Map<Key, Value> values, long version) {

    Optional<Value> find(final Key key) {
      return Optional.ofNullable(values.get(key));
    }
  }

  /**
   * Builds a {@link LibraryConfigurationFactory}. Set the application, the environment, and at
   * least one of {@link #defaults(ConfigReader)} and {@link #updates(ConfigReader)}.
   */
  public static final class Builder extends ConfigurationFactory.Builder<Builder, LibraryConfigurationFactory> {

    private ConfigReader defaults;
    private ConfigReader updates;
    private Duration refreshInterval = DEFAULT_REFRESH_INTERVAL;
    private boolean requireUpdatesAtStartup;

    private Builder() {
    }

    /**
     * Sets the defaults datastore, read once when the factory is built. If it cannot be read,
     * building fails. A later call replaces it.
     *
     * @param defaults the datastore.
     * @return this builder.
     */
    public Builder defaults(final ConfigReader defaults) {
      this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
      return this;
    }

    /**
     * Sets the updates datastore, whose values override the defaults and which is checked for
     * changes on the refresh interval. A later call replaces it.
     *
     * @param updates the datastore.
     * @return this builder.
     */
    public Builder updates(final ConfigReader updates) {
      this.updates = Objects.requireNonNull(updates, "updates must not be null");
      return this;
    }

    /**
     * How long to wait between checks of the updates datastore. Each check is one
     * {@link ConfigReader#version(SetId)} call, plus a load when the version has changed.
     * {@link Duration#ZERO} turns off the background thread; the application then calls
     * {@link LibraryConfigurationFactory#refresh()} itself. Defaults to
     * {@link #DEFAULT_REFRESH_INTERVAL}.
     *
     * @param refreshInterval the interval; zero, or at least one millisecond.
     * @return this builder.
     * @throws IllegalArgumentException if the interval is negative or under a millisecond.
     */
    public Builder refreshInterval(final Duration refreshInterval) {
      Objects.requireNonNull(refreshInterval, "refreshInterval must not be null");
      if (refreshInterval.isNegative() || (!refreshInterval.isZero() && refreshInterval.toMillis() < 1)) {
        throw new IllegalArgumentException("refreshInterval must be zero or at least 1ms: " + refreshInterval);
      }
      this.refreshInterval = refreshInterval;
      return this;
    }

    /**
     * Whether building fails when the updates datastore cannot be read. By default (false) the
     * factory serves the defaults alone and keeps trying on each refresh; true makes
     * {@link #build()} throw instead, so the application never starts on defaults it did not
     * expect.
     *
     * @param requireUpdatesAtStartup true to fail when the updates cannot be read at startup.
     * @return this builder.
     */
    public Builder requireUpdatesAtStartup(final boolean requireUpdatesAtStartup) {
      this.requireUpdatesAtStartup = requireUpdatesAtStartup;
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * {@inheritDoc} Reads the defaults, makes the first read of the updates, and starts the refresh
     * thread.
     *
     * @throws IllegalStateException    if neither datastore is set.
     * @throws ConfigDatastoreException if the defaults cannot be read, or the updates cannot be
     *                                  read and {@link #requireUpdatesAtStartup(boolean)} is set.
     */
    @Override
    protected LibraryConfigurationFactory build(final Application application, final Environment environment) {
      if (defaults == null && updates == null) {
        throw new IllegalStateException("no datastore set: set defaults, updates, or both");
      }
      return new LibraryConfigurationFactory(SetId.settings(application, environment), defaults, updates,
        refreshInterval, requireUpdatesAtStartup);
    }
  }
}
