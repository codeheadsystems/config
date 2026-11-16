package com.codeheadsystems.config.integ;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.client.ClientConfigurationFactory;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastores;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.library.LibraryConfigurationFactory;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.testoidc.TestOidcIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The whole path on one real datastore. An operator's code changes settings through the
 * datastore library (the only way configuration changes); config-server serves them over HTTP;
 * a service reads them through {@link ClientConfigurationFactory} or the Rust client
 * ({@link RustClient}), and an in-process service through {@link LibraryConfigurationFactory}.
 * Each backend runs these as its own subclass.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EndToEndContract {

  private static final String SUBJECT = "system:serviceaccount:billing:billing";
  private static final Environment ENV = Environment.from("prod");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private TestOidcIssuer issuer;
  private ConfigServer server;
  private ConfigDatastore writer;
  private Application app;

  /** The backend's provider name, as in config-server's {@code datastore.name}. */
  protected abstract String datastore();

  /** The backend's options, starting its container if needed. */
  protected abstract Map<String, String> options();

  @BeforeAll
  void start() {
    Backends.requireDocker();
    final Map<String, String> options = options();
    // The writer opens first, so it creates the schema, table or bucket layout the server reads.
    writer = ConfigDatastores.open(datastore(), Configuration.builder().with(options).build());
    issuer = TestOidcIssuer.start();
    server = new ConfigServer(datastore(), options, issuer.issuer());
  }

  @AfterAll
  void stop() {
    if (server != null) {
      server.close();
    }
    if (issuer != null) {
      issuer.close();
    }
    if (writer != null) {
      writer.close();
    }
  }

  @BeforeEach
  void newApplication() {
    app = Application.from("integ-" + UUID.randomUUID().toString().substring(0, 8));
  }

  @Test
  void bothReadersFollowChangesMadeThroughTheLibrary() {
    grant(SUBJECT);
    write(Map.of("database.url", "jdbc:postgresql://db/billing", "database.pool.size", "10"));

    try (ClientConfigurationFactory client = client(); LibraryConfigurationFactory library = library()) {
      assertThat(client.settingsVersion()).isPositive().isEqualTo(library.updatesVersion());
      for (final Configuration configuration : new Configuration[] {client.configuration(), library.configuration()}) {
        assertThat(configuration.with("database").get("url").value()).isEqualTo("jdbc:postgresql://db/billing");
        assertThat(configuration.get("database.pool.size").value()).isEqualTo("10");
      }
      final long first = client.settingsVersion();

      // Nothing changed: the client's check is answered 304.
      assertThat(client.refresh()).isFalse();
      assertThat(client.problem()).isEmpty();

      write(Map.of("database.url", "jdbc:postgresql://db/billing", "database.pool.size", "20"));

      assertThat(client.refresh()).isTrue();
      assertThat(library.refresh()).isTrue();
      assertThat(client.settingsVersion()).isGreaterThan(first).isEqualTo(library.updatesVersion());
      assertThat(client.configuration().get("database.pool.size").value()).isEqualTo("20");
      assertThat(library.configuration().get("database.pool.size").value()).isEqualTo("20");
    }
  }

  @Test
  void revokedAccessKeepsTheClientsValuesAndIsReported() {
    grant(SUBJECT);
    write(Map.of("pool", "10"));

    try (ClientConfigurationFactory client = client()) {
      grant("system:serviceaccount:other:other");

      assertThat(client.refresh()).isFalse();
      assertThat(client.problem())
          .contains("403: Not allowed to read application " + app.value() + " in environment prod");
      assertThat(client.configuration().get("pool").value()).isEqualTo("10");

      grant(SUBJECT);
      client.refresh();
      assertThat(client.problem()).isEmpty();
    }
  }

  @Test
  void aDeletedSetIsKeptByTheClientAndARecreatedOneHasAHigherVersion() {
    grant(SUBJECT);
    write(Map.of("pool", "10"));

    try (ClientConfigurationFactory client = client()) {
      final long before = client.settingsVersion();

      writer.delete(SetId.settings(app, ENV), Versions.ANY);
      assertThat(client.refresh()).isFalse();
      assertThat(client.problem())
          .contains("404: No settings for application " + app.value() + " in environment prod");
      assertThat(client.configuration().get("pool").value()).isEqualTo("10");

      write(Map.of("pool", "30"));
      // Versions never repeat, even across a delete, so the client sees the new set as newer.
      assertThat(client.refresh()).isTrue();
      assertThat(client.settingsVersion()).isGreaterThan(before);
      assertThat(client.configuration().get("pool").value()).isEqualTo("30");
    }
  }

  @Test
  void theRustClientFollowsChangesMadeThroughTheLibrary() {
    RustClient.requireDriver();
    grant(SUBJECT);
    write(Map.of("database.url", "jdbc:postgresql://db/billing", "database.pool.size", "10"));

    try (RustClient rust = rustClient(); ClientConfigurationFactory java = client()) {
      assertThat(rust.state().version()).isPositive().isEqualTo(java.settingsVersion());
      assertThat(rust.state().settings()).containsExactlyInAnyOrderEntriesOf(
          Map.of("database.url", "jdbc:postgresql://db/billing", "database.pool.size", "10"));

      // Nothing changed: the check is answered 304.
      final RustClient.State unchanged = rust.refresh();
      assertThat(unchanged.changed()).isFalse();
      assertThat(unchanged.problem()).isEmpty();
      assertThat(unchanged.version()).isEqualTo(java.settingsVersion());

      write(Map.of("database.url", "jdbc:postgresql://db/billing", "database.pool.size", "20"));

      final RustClient.State changed = rust.refresh();
      assertThat(java.refresh()).isTrue();
      assertThat(changed.changed()).isTrue();
      assertThat(changed.version()).isEqualTo(java.settingsVersion());
      assertThat(changed.settings()).containsEntry("database.pool.size", "20");
    }
  }

  @Test
  void theRustClientRefusesToStartWithoutAccess() {
    RustClient.requireDriver();
    grant("system:serviceaccount:other:other");
    write(Map.of("pool", "10"));

    assertThatThrownBy(this::rustClient)
        .hasMessageContaining("403: Not allowed to read application " + app.value() + " in environment prod");
  }

  @Test
  void theRustClientKeepsItsValuesWhenAccessIsRevokedOrTheTokenIsBad() {
    RustClient.requireDriver();
    grant(SUBJECT);
    write(Map.of("pool", "10"));

    try (RustClient rust = rustClient()) {
      grant("system:serviceaccount:other:other");
      RustClient.State state = rust.refresh();
      assertThat(state.changed()).isFalse();
      assertThat(state.problem())
          .contains("403: Not allowed to read application " + app.value() + " in environment prod");
      assertThat(state.settings()).containsEntry("pool", "10");

      grant(SUBJECT);
      // A token for another service is refused; the client reads its token file on every request.
      rust.token(issuer.token(SUBJECT).audience("another-service").sign());
      state = rust.refresh();
      assertThat(state.problem()).hasValueSatisfying(problem -> assertThat(problem).startsWith("401: "));
      assertThat(state.settings()).containsEntry("pool", "10");

      rust.token(token());
      state = rust.refresh();
      assertThat(state.problem()).isEmpty();
      assertThat(state.settings()).containsEntry("pool", "10");
    }
  }

  @Test
  void theRustClientKeepsADeletedSetAndAdoptsARecreatedOne() {
    RustClient.requireDriver();
    grant(SUBJECT);
    write(Map.of("pool", "10"));

    try (RustClient rust = rustClient()) {
      final long before = rust.state().version();

      writer.delete(SetId.settings(app, ENV), Versions.ANY);
      RustClient.State state = rust.refresh();
      assertThat(state.changed()).isFalse();
      assertThat(state.problem())
          .contains("404: No settings for application " + app.value() + " in environment prod");
      assertThat(state.settings()).containsEntry("pool", "10");

      write(Map.of("pool", "30"));
      state = rust.refresh();
      assertThat(state.changed()).isTrue();
      assertThat(state.version()).isGreaterThan(before);
      assertThat(state.settings()).containsEntry("pool", "30");
    }
  }

  @Test
  void cacheSettingsInMetadataReachTheClient() throws Exception {
    grant(SUBJECT);
    write(Map.of("pool", "10"));
    writer.put(SetId.metadata(app, ENV), Versions.ANY, Key.from("cache.max-age"), new Value("120"));

    final HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(settingsUri())
        .header("Authorization", "Bearer " + token()).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control"))
        .contains("private, max-age=120, stale-while-revalidate=120, stale-if-error=86400");
    assertThat(response.body()).doesNotContain("cache.max-age", "auth.");
  }

  @Test
  void theServersHealthCheckSeesTheDatastore() throws Exception {
    final HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(
        server.adminUri().resolve("/healthcheck")).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(health.body()).contains("\"datastore\":{\"healthy\":true");
  }

  private ClientConfigurationFactory client() {
    return ClientConfigurationFactory.builder()
        .with(app)
        .with(ENV)
        .baseUri(server.uri())
        .token(this::token)
        .autoRefresh(false)
        .requireSettingsAtStartup(true)
        .timeout(Duration.ofSeconds(10))
        .build();
  }

  private RustClient rustClient() {
    return RustClient.start(server.uri(), app.value(), ENV.value(), token());
  }

  private LibraryConfigurationFactory library() {
    return LibraryConfigurationFactory.builder()
        .with(app)
        .with(ENV)
        .updates(writer)
        .refreshInterval(Duration.ZERO)
        .requireUpdatesAtStartup(true)
        .build();
  }

  private String token() {
    return issuer.token(SUBJECT).audience(ConfigServer.AUDIENCE).sign();
  }

  private URI settingsUri() {
    return server.uri().resolve("/v1/applications/" + app.value() + "/environments/prod/settings");
  }

  /** Grants a subject access, as an operator would: through the library, in METADATA. */
  private void grant(final String subject) {
    writer.put(SetId.metadata(app, ENV), Versions.ANY,
        Key.from("auth." + ConfigServer.ISSUER_NAME + ".subjects"), new Value(subject));
  }

  /** Replaces the application's settings through the library. */
  private void write(final Map<String, String> values) {
    final Map<Key, Value> converted = new LinkedHashMap<>();
    values.forEach((key, value) -> converted.put(Key.from(key), new Value(value)));
    writer.replace(SetId.settings(app, ENV), Versions.ANY, converted);
  }
}
