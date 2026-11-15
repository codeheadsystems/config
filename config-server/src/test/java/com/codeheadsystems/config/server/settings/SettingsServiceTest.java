package com.codeheadsystems.config.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.codahale.metrics.MetricRegistry;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigReader;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.CacheConfig;
import com.codeheadsystems.config.server.api.ApiException;
import io.dropwizard.util.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsServiceTest {

  private static final Application APP = Application.from("billing");
  private static final Environment ENV = Environment.from("prod");
  private static final SetId SETTINGS = SetId.settings(APP, ENV);
  private static final SetId METADATA = SetId.metadata(APP, ENV);

  private final ConfigReader reader = mock(ConfigReader.class);
  private final MetricRegistry metrics = new MetricRegistry();

  private SettingsService service(final Duration versionTtl, final Duration metadataTtl) {
    final CacheConfig config = new CacheConfig();
    config.setVersionTtl(versionTtl);
    config.setMetadataTtl(metadataTtl);
    config.setRetryAfter(12);
    return new SettingsService(reader, config, metrics);
  }

  @Test
  void aFailingDatastoreIs503WithRetryAfterAndIsCounted() {
    when(reader.load(any())).thenThrow(new ConfigDatastoreException("connection refused"));
    when(reader.version(any())).thenThrow(new IllegalStateException("driver bug"));
    final SettingsService service = service(Duration.seconds(1), Duration.seconds(1));

    for (final Runnable read : new Runnable[] {
        () -> service.load(SETTINGS), () -> service.version(SETTINGS), () -> service.metadata(APP, ENV)}) {
      assertThatThrownBy(read::run)
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(503);
            assertThat(e.headers()).containsEntry("Retry-After", "12");
          });
    }
    assertThat(metrics.meter("com.codeheadsystems.config.server.settings.SettingsService.failures").getCount())
        .isEqualTo(3);
  }

  @Test
  void failuresAreNotCached() {
    when(reader.version(SETTINGS)).thenThrow(new ConfigDatastoreException("down")).thenReturn(3L);
    final SettingsService service = service(Duration.minutes(1), Duration.minutes(1));

    assertThatThrownBy(() -> service.version(SETTINGS)).isInstanceOf(ApiException.class);
    assertThat(service.version(SETTINGS)).isEqualTo(3);
  }

  @Test
  void versionsAreCachedForTheirTtl() {
    when(reader.version(SETTINGS)).thenReturn(4L, 5L);
    final SettingsService service = service(Duration.minutes(1), Duration.minutes(1));

    assertThat(service.version(SETTINGS)).isEqualTo(4);
    assertThat(service.version(SETTINGS)).isEqualTo(4);
    verify(reader, times(1)).version(SETTINGS);
    assertThat(metrics.timer("com.codeheadsystems.config.server.settings.SettingsService.version").getCount())
        .isEqualTo(1);
  }

  @Test
  void aFreshLoadMovesTheCachedVersionForwardButNeverBack() {
    when(reader.version(SETTINGS)).thenReturn(4L);
    when(reader.load(SETTINGS)).thenReturn(snapshot(SETTINGS, 6)).thenReturn(snapshot(SETTINGS, 5));
    final SettingsService service = service(Duration.minutes(1), Duration.minutes(1));
    assertThat(service.version(SETTINGS)).isEqualTo(4);

    service.load(SETTINGS);
    assertThat(service.version(SETTINGS)).isEqualTo(6);

    service.load(SETTINGS);
    assertThat(service.version(SETTINGS)).isEqualTo(6);
  }

  @Test
  void metadataIsCachedForItsTtlIncludingWhenThereIsNone() {
    when(reader.load(METADATA)).thenReturn(Optional.empty()).thenReturn(snapshot(METADATA, 1));
    final SettingsService service = service(Duration.minutes(1), Duration.minutes(1));

    assertThat(service.metadata(APP, ENV)).isEmpty();
    assertThat(service.metadata(APP, ENV)).isEmpty();
    verify(reader, times(1)).load(METADATA);
  }

  @Test
  void aZeroTtlReadsEveryTime() {
    when(reader.load(METADATA)).thenReturn(Optional.empty()).thenReturn(snapshot(METADATA, 1));
    final SettingsService service = service(Duration.seconds(0), Duration.seconds(0));

    assertThat(service.metadata(APP, ENV)).isEmpty();
    assertThat(service.metadata(APP, ENV)).containsKey(Key.from("k"));
  }

  private static Optional<Snapshot> snapshot(final SetId id, final long version) {
    return Optional.of(new Snapshot(id, Map.of(Key.from("k"), new Value("v")), version));
  }
}
