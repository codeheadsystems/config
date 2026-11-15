package com.codeheadsystems.config.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.testoidc.TestOidcIssuer;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The real server with the behaviour {@code ConfigServerApplicationTest} switches off: server-side
 * caches, the JWKS refresh limit, the per-token rate limit, and datastore failures
 * ({@link FlakyDatastore}). TTLs are an hour, so anything cached stays cached for the test.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class ConfigServerProductionSettingsTest {

  private static final TestOidcIssuer ISSUER = TestOidcIssuer.start();
  private static final TestOidcIssuer ROTATING = TestOidcIssuer.start();
  private static final String SUBJECT = "system:serviceaccount:billing:billing";
  private static final AtomicInteger APPS = new AtomicInteger();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final DropwizardAppExtension<ConfigServerConfiguration> APP = new DropwizardAppExtension<>(
      ConfigServerApplication.class,
      ResourceHelpers.resourceFilePath("test-config-production.yml"),
      ConfigOverride.config("auth.issuers[0].issuer", () -> ISSUER.issuer().toString()),
      ConfigOverride.config("auth.issuers[1].issuer", () -> ROTATING.issuer().toString()));

  private String app;

  @BeforeEach
  void newApplication() {
    app = "prod-app-" + APPS.incrementAndGet();
    grant("test", SUBJECT);
  }

  @AfterEach
  void restoreDatastore() {
    FlakyDatastore.MODE.set(FlakyDatastore.Mode.WORKING);
  }

  @AfterAll
  static void stopIssuers() {
    ISSUER.close();
    ROTATING.close();
  }

  @Test
  void theIssuersKeysAreFetchedOnceAndCached() throws Exception {
    settings(Map.of("a", "1"));
    for (int i = 0; i < 5; i++) {
      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);
    }

    assertThat(ISSUER.discoveryRequests()).isEqualTo(1);
    assertThat(ISSUER.jwksRequests()).isEqualTo(1);
  }

  @Test
  void aKeyTheServerCannotFetchYetIs503NotARejection() throws Exception {
    grant("rotating", SUBJECT);
    settings(Map.of("a", "1"));
    assertThat(send("GET", settingsPath(), ROTATING.token(SUBJECT).sign()).statusCode()).isEqualTo(200);

    ROTATING.rotateKey();
    // A rotation's first unknown kid may refetch the JWKS (the second fetch in the window) ...
    assertThat(send("GET", settingsPath(), ROTATING.token(SUBJECT).sign()).statusCode()).isEqualTo(200);

    ROTATING.rotateKey();
    // ... but a third fetch within the hour is refused, and that is not the client's fault.
    final HttpResponse<String> limited = send("GET", settingsPath(), ROTATING.token(SUBJECT).sign());
    assertThat(limited.statusCode()).isEqualTo(503);
    assertThat(limited.headers().firstValue("Retry-After")).contains("3600");
    assertThat(limited.body()).isEqualTo("{\"message\":\"The token's signing key is not known yet\"}");
    assertThat(ROTATING.jwksRequests()).isEqualTo(2);
  }

  @Test
  void metadataIsCachedSoAccessChangesTakeUpToTheTtl() throws Exception {
    settings(Map.of("a", "1"));
    assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);

    grant("test", "someone-else");

    assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);
  }

  @Test
  void theVersionCanLagButNeverGoesBackward() throws Exception {
    settings(Map.of("a", "1"));
    assertThat(version(send("HEAD", settingsPath(), token()))).isEqualTo("1");

    settings(Map.of("a", "2"));
    // HEAD answers from the version cache: up to versionTtl old.
    assertThat(version(send("HEAD", settingsPath(), token()))).isEqualTo("1");
    // GET reads the set fresh ...
    assertThat(version(send("GET", settingsPath(), token()))).isEqualTo("2");
    // ... and from then on HEAD never reports an older version.
    assertThat(version(send("HEAD", settingsPath(), token()))).isEqualTo("2");
  }

  @Test
  void anUnreachableDatastoreIs503WithRetryAfter() throws Exception {
    settings(Map.of("a", "1"));
    FlakyDatastore.MODE.set(FlakyDatastore.Mode.UNREACHABLE);

    final HttpResponse<String> response = send("GET", settingsPath(), token());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Retry-After")).contains("17");
    assertThat(response.body()).isEqualTo("{\"message\":\"The datastore cannot be reached\"}");
  }

  @Test
  void aDatastoreFailureIsNotCached() throws Exception {
    settings(Map.of("a", "1"));
    FlakyDatastore.MODE.set(FlakyDatastore.Mode.UNREACHABLE);
    assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(503);

    FlakyDatastore.MODE.set(FlakyDatastore.Mode.WORKING);

    assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);
  }

  @Test
  void anUnexpectedErrorIs500WithoutDetails() throws Exception {
    FlakyDatastore.MODE.set(FlakyDatastore.Mode.BROKEN);

    final HttpResponse<String> response = send("GET", settingsPath(), token());

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).isEqualTo("{\"message\":\"Internal error\"}");
  }

  @Test
  void eachTokenIsRateLimited() throws Exception {
    settings(Map.of("a", "1"));
    final String token = token();
    for (int i = 0; i < 3; i++) {
      assertThat(send("GET", settingsPath(), token).statusCode()).as("request %d", i + 1).isEqualTo(200);
    }

    final HttpResponse<String> limited = send("GET", settingsPath(), token);

    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(
        seconds -> assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
    assertThat(limited.body()).isEqualTo("{\"message\":\"Too many requests\"}");
    // Another instance of the same service, with its own token, is not affected.
    assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);
  }

  @Test
  void healthChecksCoverTheDatastoreAndEachIssuer() throws Exception {
    final HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + APP.getAdminPort() + "/healthcheck")).build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("\"datastore\":{\"healthy\":true", "\"issuer-test\":{\"healthy\":true",
        "\"issuer-rotating\":{\"healthy\":true");
  }

  private static String version(final HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(200);
    return response.headers().firstValue("Config-Version").orElseThrow();
  }

  private String settingsPath() {
    return "/v1/applications/" + app + "/environments/prod/settings";
  }

  private static String token() {
    return ISSUER.token(SUBJECT).sign();
  }

  private static ConfigDatastore datastore() {
    return ((ConfigServerApplication) APP.getApplication()).datastore();
  }

  private void grant(final String issuer, final String... subjects) {
    datastore().put(SetId.metadata(Application.from(app), Environment.from("prod")), Versions.ANY,
        Key.from("auth." + issuer + ".subjects"), new Value(String.join(" ", subjects)));
  }

  private void settings(final Map<String, String> values) {
    final Map<Key, Value> converted = new java.util.LinkedHashMap<>();
    values.forEach((key, value) -> converted.put(Key.from(key), new Value(value)));
    datastore().replace(SetId.settings(Application.from(app), Environment.from("prod")), Versions.ANY, converted);
  }

  private static HttpResponse<String> send(final String method, final String path, final String token)
      throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + APP.getLocalPort() + path))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .header("Authorization", "Bearer " + token)
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
