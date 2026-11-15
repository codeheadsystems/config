package com.codeheadsystems.config.server.settings;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codeheadsystems.config.datastore.ConfigReader;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.CacheConfig;
import com.codeheadsystems.config.server.api.ApiException;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads sets for the resource, with short server-side caches for versions and METADATA, so a
 * burst of version checks or access checks costs one datastore query. Settings values are always
 * read fresh. A datastore failure becomes {@code 503} with {@code Retry-After}; failures are not
 * cached, so one bad set cannot make other sets fail.
 *
 * <p>Datastore calls are timed in the metric registry ({@code SettingsService.load},
 * {@code .version}, {@code .metadata}) and failures counted ({@code .failures}).
 */
@Singleton
public final class SettingsService {

  private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);
  private static final long MAX_CACHED_SETS = 10_000;

  private final ConfigReader reader;
  private final CacheConfig config;
  private final LoadingCache<SetId, Long> versions;
  private final LoadingCache<SetId, Map<Key, Value>> metadata;
  private final Timer loadTimer;
  private final Meter failures;

  /**
   * Creates the service.
   *
   * @param reader  the datastore.
   * @param config  cache times and defaults.
   * @param metrics where datastore timings go.
   */
  @Inject
  public SettingsService(final ConfigReader reader, final CacheConfig config, final MetricRegistry metrics) {
    this.reader = reader;
    this.config = config;
    this.loadTimer = metrics.timer(MetricRegistry.name(SettingsService.class, "load"));
    this.failures = metrics.meter(MetricRegistry.name(SettingsService.class, "failures"));
    final Timer versionTimer = metrics.timer(MetricRegistry.name(SettingsService.class, "version"));
    final Timer metadataTimer = metrics.timer(MetricRegistry.name(SettingsService.class, "metadata"));
    this.versions = Caffeine.newBuilder()
        .expireAfterWrite(config.getVersionTtl().toJavaDuration())
        .maximumSize(MAX_CACHED_SETS)
        .build(id -> guarded(versionTimer, () -> reader.version(id)));
    this.metadata = Caffeine.newBuilder()
        .expireAfterWrite(config.getMetadataTtl().toJavaDuration())
        .maximumSize(MAX_CACHED_SETS)
        .build(id -> guarded(metadataTimer, () -> reader.load(id).map(Snapshot::values).orElse(Map.of())));
  }

  /**
   * A set's version, possibly up to the version TTL old, but never older than a version this
   * server has already served from a fresh {@link #load}.
   *
   * @param id the set.
   * @return the version, or {@code Versions.NONE} if the set does not exist.
   */
  long version(final SetId id) {
    return versions.get(id);
  }

  /**
   * Loads a set, uncached. A newer version than the cached one replaces it, so a client never sees
   * this server's version go backward between a {@code GET} and a {@code HEAD}.
   *
   * @param id the set.
   * @return the set, or empty if it does not exist.
   */
  Optional<Snapshot> load(final SetId id) {
    final Optional<Snapshot> snapshot = guarded(loadTimer, () -> reader.load(id));
    snapshot.ifPresent(found -> versions.asMap().merge(id, found.version(), Math::max));
    return snapshot;
  }

  /**
   * An application's METADATA values in an environment, possibly up to the METADATA TTL old.
   *
   * @param application the application.
   * @param environment the environment.
   * @return the values; empty if there is no METADATA set.
   */
  Map<Key, Value> metadata(final Application application, final Environment environment) {
    return metadata.get(SetId.metadata(application, environment));
  }

  /**
   * The cache settings, for the {@code Cache-Control} defaults.
   *
   * @return the settings.
   */
  CacheConfig cacheConfig() {
    return config;
  }

  private <T> T guarded(final Timer timer, final Supplier<T> read) {
    final Timer.Context timing = timer.time();
    try {
      return read.get();
    } catch (final ApiException e) {
      throw e;
    } catch (final RuntimeException e) {
      failures.mark();
      LOG.warn("Datastore read failed", e);
      throw ApiException.unavailable("The datastore cannot be reached", config.getRetryAfter());
    } finally {
      timing.stop();
    }
  }
}
