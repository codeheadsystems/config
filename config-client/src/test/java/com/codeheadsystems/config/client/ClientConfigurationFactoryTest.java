package com.codeheadsystems.config.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigurationFactoryTest {

  private static final Map<String, String> V42 = Map.of("database.url", "jdbc:postgresql://db/billing", "pool", "10");

  private final FakeConfigService service = new FakeConfigService();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
  private ClientConfigurationFactory factory;

  @AfterEach
  void close() {
    if (factory != null) {
      factory.close();
    }
    service.close();
  }

  private ClientConfigurationFactory.Builder builder() {
    return ClientConfigurationFactory.builder()
        .with(Application.from("billing"))
        .with(Environment.from("prod"))
        .baseUri(service.uri())
        .token(() -> "token-1")
        .autoRefresh(false)
        .clock(clock)
        .jitter(() -> 0.0);
  }

  private ClientConfigurationFactory start() {
    factory = builder().build();
    return factory;
  }

  @Nested
  class Loading {

    @Test
    void loadsTheSettingsAtStartupWithTheToken() {
      service.settings(42, V42);

      start();

      assertThat(factory.settingsLoaded()).isTrue();
      assertThat(factory.settingsVersion()).isEqualTo(42);
      assertThat(factory.configuration().get("database.url").value()).isEqualTo("jdbc:postgresql://db/billing");
      assertThat(factory.configuration().with("database").get("url").value()).isEqualTo("jdbc:postgresql://db/billing");
      assertThat(factory.problem()).isEmpty();
      assertThat(factory.lastChecked()).contains(clock.instant());
      final FakeConfigService.Request request = service.requests().get(0);
      assertThat(request.method()).isEqualTo("GET");
      assertThat(request.path()).isEqualTo("/v1/applications/billing/environments/prod/settings");
      assertThat(request.authorization()).isEqualTo("Bearer token-1");
      assertThat(request.ifNoneMatch()).isNull();
    }

    @Test
    void laterChecksAskWithTheVersionHeldAnd304KeepsTheValues() {
      service.settings(42, V42).notModified(42);
      start();
      clock.advance(Duration.ofSeconds(30));

      assertThat(factory.refresh()).isFalse();

      assertThat(service.requests().get(1).ifNoneMatch()).isEqualTo("\"42\"");
      assertThat(factory.settingsVersion()).isEqualTo(42);
      assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
      assertThat(factory.lastChecked()).contains(clock.instant());
    }

    @Test
    void aNewerVersionReplacesTheValuesInConfigurationsAlreadyHandedOut() {
      service.settings(42, V42).settings(43, Map.of("pool", "20"));
      start();
      final Configuration configuration = factory.configuration();

      assertThat(factory.refresh()).isTrue();

      assertThat(factory.settingsVersion()).isEqualTo(43);
      assertThat(configuration.get("pool").value()).isEqualTo("20");
      assertThat(configuration.find("database.url")).isEmpty();
    }

    @Test
    void anOlderVersionFromALaggingServerIsIgnored() {
      service.settings(42, V42).settings(41, Map.of("pool", "1"));
      start();

      assertThat(factory.refresh()).isFalse();

      assertThat(factory.settingsVersion()).isEqualTo(42);
      assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
      assertThat(factory.problem()).isEmpty();
    }
  }

  @Nested
  class Errors {

    @Test
    void everyErrorKeepsTheValuesAndIsReportedUntilACheckSucceeds() {
      service.settings(42, V42)
          .error(401, "The access token expired")
          .error(403, "Not allowed to read application billing in environment prod")
          .error(404, "No settings for application billing in environment prod")
          .error(500, "Internal error")
          .notModified(42);
      start();

      for (final String expected : new String[] {
          "401: The access token expired",
          "403: Not allowed to read application billing in environment prod",
          "404: No settings for application billing in environment prod",
          "500: Internal error"}) {
        assertThat(factory.refresh()).isFalse();
        assertThat(factory.problem()).contains(expected);
        assertThat(factory.settingsVersion()).isEqualTo(42);
        assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
      }

      factory.refresh();
      assertThat(factory.problem()).isEmpty();
    }

    @Test
    void anUnreachableServiceKeepsTheValues() {
      service.settings(42, V42);
      start();
      service.close();

      assertThat(factory.refresh()).isFalse();

      assertThat(factory.problem()).hasValueSatisfying(problem ->
          assertThat(problem).startsWith("cannot reach the config service"));
      assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
    }

    @Test
    void aRefusedTokenIsRetriedOnceAtOnceWhenANewTokenIsAvailable() {
      final AtomicReference<String> token = new AtomicReference<>("old-token");
      service.error(401, "The access token expired").settings(42, V42);

      factory = builder().token(() -> {
        final String current = token.get();
        token.set("new-token"); // the platform renews the token after the first read
        return current;
      }).build();

      assertThat(factory.settingsVersion()).isEqualTo(42);
      assertThat(service.requests()).extracting(FakeConfigService.Request::authorization)
          .containsExactly("Bearer old-token", "Bearer new-token");
    }

    @Test
    void aRefusedTokenIsNotRetriedWhenItHasNotChanged() {
      service.error(401, "The access token is not valid");

      start();

      assertThat(service.requests()).hasSize(1);
      assertThat(factory.problem()).contains("401: The access token is not valid");
    }

    @Test
    void aTokenThatCannotBeReadIsAProblemNotACrash(@TempDir final Path dir) {
      factory = builder().tokenFile(dir.resolve("missing")).build();

      assertThat(service.requests()).isEmpty();
      assertThat(factory.problem()).hasValueSatisfying(problem ->
          assertThat(problem).startsWith("cannot get a bearer token"));
    }

    @Test
    void theTokenFileIsReadForEveryRequest(@TempDir final Path dir) throws Exception {
      final Path file = dir.resolve("token");
      Files.writeString(file, "first\n");
      service.settings(42, V42).notModified(42);
      factory = builder().tokenFile(file).build();

      Files.writeString(file, "second\n");
      factory.refresh();

      assertThat(service.requests()).extracting(FakeConfigService.Request::authorization)
          .containsExactly("Bearer first", "Bearer second");
    }
  }

  @Nested
  class Startup {

    @Test
    void defaultsAnswerUntilSettingsArriveThenOnlyForMissingKeys() {
      service.error(503, "The datastore cannot be reached", Map.of("Retry-After", "5"))
          .settings(42, Map.of("pool", "10"));
      factory = builder()
          .defaults(Configuration.builder().with(Map.of("pool", "1", "timeout", "5s")).build())
          .build();

      assertThat(factory.settingsLoaded()).isFalse();
      assertThat(factory.configuration().get("pool").value()).isEqualTo("1");

      factory.refresh();

      assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
      assertThat(factory.configuration().get("timeout").value()).isEqualTo("5s");
    }

    @Test
    void requireSettingsAtStartupFailsTheBuild() {
      service.error(403, "Not allowed to read application billing in environment prod");

      assertThatThrownBy(() -> builder().requireSettingsAtStartup(true).build())
          .isInstanceOf(ConfigClientException.class)
          .hasMessage("Cannot read billing/prod settings from the config service: "
              + "403: Not allowed to read application billing in environment prod");
    }

    @Test
    void theBuilderNeedsAServiceAndAToken() {
      assertThatThrownBy(() -> ClientConfigurationFactory.builder().with(Application.from("billing"))
          .with(Environment.from("prod")).token(() -> "t").build())
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("baseUri");
      assertThatThrownBy(() -> ClientConfigurationFactory.builder().with(Application.from("billing"))
          .with(Environment.from("prod")).baseUri(service.uri()).build())
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("token");
    }
  }

  @Nested
  class Timing {

    @Test
    void theNextCheckIsMaxAgeFromTheServer() {
      service.settings(42, V42, "private, max-age=120, stale-while-revalidate=120, stale-if-error=600");

      start();

      assertThat(factory.nextCheck()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void jitterAddsUpToTenPercent() {
      service.settings(42, V42);
      factory = builder().jitter(() -> 1.0).build();

      assertThat(factory.nextCheck()).isEqualTo(Duration.ofSeconds(33));
    }

    @Test
    void neverChecksMoreOftenThanOnceASecond() {
      service.settings(42, V42, "private, max-age=0, stale-while-revalidate=0, stale-if-error=0");

      start();

      assertThat(factory.nextCheck()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void retryAfterIsHonoredWhenLongerThanMaxAge() {
      service.settings(42, V42)
          .error(429, "Too many requests", Map.of("Retry-After", "120"))
          .error(503, "The datastore cannot be reached", Map.of("Retry-After", "5"));
      start();

      factory.refresh();
      assertThat(factory.nextCheck()).isEqualTo(Duration.ofSeconds(120));

      factory.refresh();
      assertThat(factory.nextCheck()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void settingsGoStaleOnlyAfterMaxAgePlusStaleIfErrorWithoutASuccessfulCheck() {
      service.settings(42, V42, "private, max-age=30, stale-while-revalidate=30, stale-if-error=600");
      start();
      assertThat(factory.stale()).isFalse();

      clock.advance(Duration.ofSeconds(630));
      assertThat(factory.stale()).isFalse();

      clock.advance(Duration.ofSeconds(1));
      assertThat(factory.stale()).isTrue();
      assertThat(factory.configuration().get("pool").value()).isEqualTo("10");
    }

    @Test
    void neverLoadedIsStale() {
      service.error(503, "The datastore cannot be reached");

      start();

      assertThat(factory.stale()).isTrue();
    }

    @Test
    void theBackgroundThreadChecksAfterMaxAgeUntilClosed() throws Exception {
      final String everySecond = "private, max-age=1, stale-while-revalidate=1, stale-if-error=60";
      service.settings(42, V42, everySecond).settings(43, Map.of("pool", "20"), everySecond);
      factory = builder().autoRefresh(true).clock(Clock.systemUTC()).build();

      final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (factory.settingsVersion() != 43 && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(factory.settingsVersion()).isEqualTo(43);
      assertThat(service.requests().get(1).ifNoneMatch()).isEqualTo("\"42\"");

      factory.close();
      final int requests = service.requests().size();
      Thread.sleep(1_500);
      assertThat(service.requests()).hasSize(requests);
    }
  }

  @Nested
  class CacheControl {

    @Test
    void parsesTheDirectives() {
      assertThat(CachePolicy.parse(java.util.Optional.of(
          "private, max-age=120, stale-while-revalidate=60, stale-if-error=600")))
          .isEqualTo(new CachePolicy(Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofSeconds(600)));
    }

    @Test
    void missingOrMalformedDirectivesUseTheSpecDefaults() {
      assertThat(CachePolicy.parse(java.util.Optional.empty())).isEqualTo(CachePolicy.DEFAULT);
      assertThat(CachePolicy.parse(java.util.Optional.of("private, max-age=soon, stale-if-error=-1")))
          .isEqualTo(CachePolicy.DEFAULT);
      assertThat(CachePolicy.parse(java.util.Optional.of("MAX-AGE=10")))
          .isEqualTo(new CachePolicy(Duration.ofSeconds(10), Duration.ofSeconds(10), CachePolicy.DEFAULT_STALE_IF_ERROR));
    }
  }

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(final Instant now) {
      this.now = now;
    }

    void advance(final Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
