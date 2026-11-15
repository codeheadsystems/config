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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs the real server (Dropwizard, Dagger, Jersey) over HTTP against an in-memory datastore and
 * the test OIDC issuer, and checks each response the spec describes.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class ConfigServerApplicationTest {

  private static final TestOidcIssuer ISSUER = TestOidcIssuer.start();
  private static final String SUBJECT = "system:serviceaccount:billing:billing";
  private static final String DEFAULT_CACHE_CONTROL =
      "private, max-age=30, stale-while-revalidate=30, stale-if-error=86400";
  private static final AtomicInteger APPS = new AtomicInteger();

  private static final DropwizardAppExtension<ConfigServerConfiguration> APP = new DropwizardAppExtension<>(
      ConfigServerApplication.class,
      ResourceHelpers.resourceFilePath("test-config.yml"),
      ConfigOverride.config("auth.issuers[0].issuer", () -> ISSUER.issuer().toString()));

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** Each test reads its own application, so tests do not share sets. */
  private String app;

  @BeforeEach
  void newApplication() {
    app = "app-" + APPS.incrementAndGet();
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Nested
  class Authentication {

    @Test
    void noTokenIs401WithABareChallenge() throws Exception {
      final HttpResponse<String> response = send("GET", settingsPath(), null);

      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
      assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
          type -> assertThat(type).startsWith("application/json"));
      assertThat(response.body()).isEqualTo("{\"message\":\"A bearer token is required\"}");
    }

    @Test
    void anotherSchemeIs401() throws Exception {
      final HttpResponse<String> response = send("GET", settingsPath(), null, "Authorization", "Basic dXNlcjpwYXNz");

      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void anExpiredTokenSaysSo() throws Exception {
      final HttpResponse<String> response =
          send("GET", settingsPath(), ISSUER.token(SUBJECT).expiresIn(Duration.ofMinutes(-5)).sign());

      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate")).contains(
          "Bearer error=\"invalid_token\", error_description=\"The access token expired\"");
      assertThat(response.body()).isEqualTo("{\"message\":\"The access token expired\"}");
    }

    @Test
    void invalidTokensAre401() throws Exception {
      final Map<String, String> tokens = new LinkedHashMap<>();
      tokens.put("not a JWT", "not-a-jwt");
      tokens.put("wrong audience", ISSUER.token(SUBJECT).audience("another-service").sign());
      tokens.put("untrusted issuer", ISSUER.token(SUBJECT).issuer("https://elsewhere.example").sign());
      tokens.put("no issuer", ISSUER.token(SUBJECT).without("iss").sign());
      tokens.put("untrusted key", ISSUER.token(SUBJECT).signedWithUntrustedKey().sign());
      tokens.put("unknown kid", ISSUER.token(SUBJECT).keyId("never-published").sign());
      tokens.put("alg none", ISSUER.token(SUBJECT).unsigned().sign());
      tokens.put("HS256 with the public key", ISSUER.token(SUBJECT).hmacWithPublicKey().sign());
      tokens.put("no sub", ISSUER.token(SUBJECT).without("sub").sign());
      tokens.put("no exp", ISSUER.token(SUBJECT).without("exp").sign());
      tokens.put("not yet valid", ISSUER.token(SUBJECT).notBefore(Instant.now().plusSeconds(600)).sign());
      tokens.put("unexpected typ", ISSUER.token(SUBJECT).type("id+jwt").sign());
      tokens.put("encrypted (JWE)", "eyJhbGciOiJSU0EtT0FFUCIsImVuYyI6IkEyNTZHQ00ifQ.AAAA.AAAA.AAAA.AAAA");
      tokens.put("empty sub", ISSUER.token(SUBJECT).claim("sub", "").sign());
      tokens.put("blank sub", ISSUER.token(SUBJECT).claim("sub", "  ").sign());
      grant(SUBJECT);

      tokens.forEach((what, token) -> {
        final HttpResponse<String> response = sendUnchecked("GET", settingsPath(), token);
        assertThat(response.statusCode()).as(what).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).as(what)
            .hasValueSatisfying(challenge -> assertThat(challenge).startsWith("Bearer error=\"invalid_token\""));
      });
    }

    @Test
    void validTokensWithinTheClockSkewAndRfc9068TypesAreAccepted() throws Exception {
      grant(SUBJECT);
      settings(Map.of("a", "1"));

      assertThat(send("GET", settingsPath(), ISSUER.token(SUBJECT).type("at+jwt").sign()).statusCode())
          .isEqualTo(200);
      assertThat(send("GET", settingsPath(), ISSUER.token(SUBJECT).without("typ").sign()).statusCode())
          .isEqualTo(200);
      assertThat(send("GET", settingsPath(), ISSUER.token(SUBJECT).expiresIn(Duration.ofSeconds(-30)).sign())
          .statusCode()).isEqualTo(200);
      assertThat(send("GET", settingsPath(), ISSUER.token(SUBJECT).audience("other", "config-server").sign())
          .statusCode()).isEqualTo(200);
    }

    @Test
    void rotatedKeysAreFetchedWithoutARestart() throws Exception {
      grant(SUBJECT);
      settings(Map.of("a", "1"));
      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);

      ISSUER.rotateKey();

      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(200);
    }

    @Test
    void anUnreachableIssuerIs503() throws Exception {
      final HttpResponse<String> response =
          send("GET", settingsPath(), ISSUER.token(SUBJECT).issuer("http://127.0.0.1:1").sign());

      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(response.headers().firstValue("Retry-After")).contains("17");
      assertThat(response.body()).isEqualTo("{\"message\":\"The token issuer cannot be reached\"}");
    }
  }

  @Nested
  class Access {

    @Test
    void invalidNamesAre400BeforeAccessIsChecked() throws Exception {
      final HttpResponse<String> response =
          send("GET", "/v1/applications/Billing/environments/prod/settings", token());

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.body()).isEqualTo("{\"message\":\"Invalid application 'Billing'\"}");
    }

    @Test
    void noAccessListIs403() throws Exception {
      settings(Map.of("a", "1"));

      final HttpResponse<String> response = send("GET", settingsPath(), token());

      assertThat(response.statusCode()).isEqualTo(403);
      // Access is granted by the server, so no bearer challenge: a new token would not help.
      assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
      assertThat(response.body())
          .isEqualTo("{\"message\":\"Not allowed to read application " + app + " in environment prod\"}");
    }

    @Test
    void anotherSubjectIs403() throws Exception {
      grant("system:serviceaccount:ops:ops");
      settings(Map.of("a", "1"));

      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(403);
    }

    @Test
    void theSubjectMustBeListedUnderItsOwnIssuer() throws Exception {
      datastore().put(SetId.metadata(application(), environment()), Versions.ANY,
          Key.from("auth.down.subjects"), new Value(SUBJECT));
      settings(Map.of("a", "1"));

      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(403);
    }

    @Test
    void missingSettingsAre403WithoutAccessSoTheyCannotBeDiscovered() throws Exception {
      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(403);
      assertThat(send("HEAD", settingsPath(), token()).statusCode()).isEqualTo(403);
      assertThat(send("GET", settingsPath() + "/a", token()).statusCode()).isEqualTo(403);
    }

    @Test
    void aVersionCheckWithoutAccessIs403NeverNotModified() throws Exception {
      settings(Map.of("a", "1"));

      assertThat(send("GET", settingsPath(), token(), "If-None-Match", "\"1\"").statusCode()).isEqualTo(403);
      assertThat(send("HEAD", settingsPath(), token(), "If-None-Match", "*").statusCode()).isEqualTo(403);
    }

    @Test
    void anEmptyAccessListGrantsNobody() throws Exception {
      grant("");
      settings(Map.of("a", "1"));

      assertThat(send("GET", settingsPath(), token()).statusCode()).isEqualTo(403);
    }

    @Test
    void missingSettingsAre404WithAccess() throws Exception {
      grant(SUBJECT);

      final HttpResponse<String> response = send("GET", settingsPath(), token());

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body())
          .isEqualTo("{\"message\":\"No settings for application " + app + " in environment prod\"}");
      assertThat(send("HEAD", settingsPath(), token()).statusCode()).isEqualTo(404);
    }
  }

  @Nested
  class Settings {

    @BeforeEach
    void grantAccess() {
      grant(SUBJECT);
    }

    @Test
    void getReturnsTheSetWithItsVersionInThreePlaces() throws Exception {
      settings(Map.of("database.url", "jdbc:postgresql://db:5432/billing", "database.pool.size", "10"));

      final HttpResponse<String> response = send("GET", settingsPath(), token());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("{\"application\":\"" + app + "\",\"environment\":\"prod\","
          + "\"version\":1,\"values\":{\"database.pool.size\":\"10\","
          + "\"database.url\":\"jdbc:postgresql://db:5432/billing\"}}");
      assertThat(response.headers().firstValue("ETag")).contains("\"1\"");
      assertThat(response.headers().firstValue("Config-Version")).contains("1");
      assertThat(response.headers().firstValue("Cache-Control")).contains(DEFAULT_CACHE_CONTROL);
    }

    @Test
    void getWithTheCurrentVersionIs304UntilTheSetChanges() throws Exception {
      settings(Map.of("a", "1"));

      final HttpResponse<String> unchanged = send("GET", settingsPath(), token(), "If-None-Match", "\"1\"");

      assertThat(unchanged.statusCode()).isEqualTo(304);
      assertThat(unchanged.body()).isEmpty();
      assertThat(unchanged.headers().firstValue("ETag")).contains("\"1\"");
      assertThat(unchanged.headers().firstValue("Config-Version")).contains("1");
      assertThat(unchanged.headers().firstValue("Cache-Control")).contains(DEFAULT_CACHE_CONTROL);

      settings(Map.of("a", "2"));
      final HttpResponse<String> changed = send("GET", settingsPath(), token(), "If-None-Match", "\"1\"");

      assertThat(changed.statusCode()).isEqualTo(200);
      assertThat(changed.headers().firstValue("ETag")).contains("\"2\"");
      assertThat(changed.body()).contains("\"version\":2", "\"a\":\"2\"");
    }

    @Test
    void ifNoneMatchAcceptsStarWeakTagsAndLists() throws Exception {
      settings(Map.of("a", "1"));

      for (final String ifNoneMatch : new String[] {"*", "W/\"1\"", "\"0\", \"1\""}) {
        assertThat(send("GET", settingsPath(), token(), "If-None-Match", ifNoneMatch).statusCode())
            .as(ifNoneMatch).isEqualTo(304);
        assertThat(send("HEAD", settingsPath(), token(), "If-None-Match", ifNoneMatch).statusCode())
            .as(ifNoneMatch).isEqualTo(304);
      }
      assertThat(send("GET", settingsPath(), token(), "If-None-Match", "\"1\"junk").statusCode()).isEqualTo(200);
    }

    @Test
    void headReturnsTheVersionWithoutABody() throws Exception {
      settings(Map.of("a", "1"));

      final HttpResponse<String> first = send("HEAD", settingsPath(), token());
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(first.body()).isEmpty();
      assertThat(first.headers().firstValue("ETag")).contains("\"1\"");
      assertThat(first.headers().firstValue("Config-Version")).contains("1");
      assertThat(first.headers().firstValue("Cache-Control")).contains(DEFAULT_CACHE_CONTROL);
      // RFC 9110 section 8.6: no Content-Length unless it is what GET would send.
      assertThat(first.headers().firstValue("Content-Length")).isEmpty();

      final HttpResponse<String> unchanged = send("HEAD", settingsPath(), token(), "If-None-Match", "\"1\"");
      assertThat(unchanged.statusCode()).isEqualTo(304);
      assertThat(unchanged.headers().firstValue("Config-Version")).contains("1");

      settings(Map.of("a", "2"));
      final HttpResponse<String> changed = send("HEAD", settingsPath(), token(), "If-None-Match", "\"1\"");
      assertThat(changed.statusCode()).isEqualTo(200);
      assertThat(changed.headers().firstValue("Config-Version")).contains("2");
    }

    @Test
    void oneKey() throws Exception {
      settings(Map.of("database.url", "jdbc:postgresql://db:5432/billing"));

      final HttpResponse<String> found = send("GET", settingsPath() + "/database.url", token());
      assertThat(found.statusCode()).isEqualTo(200);
      assertThat(found.body()).isEqualTo("{\"key\":\"database.url\",\"value\":\"jdbc:postgresql://db:5432/billing\"}");
      assertThat(found.headers().firstValue("Config-Version")).contains("1");

      final HttpResponse<String> missing = send("GET", settingsPath() + "/database.user", token());
      assertThat(missing.statusCode()).isEqualTo(404);
      assertThat(missing.body()).isEqualTo(
          "{\"message\":\"No setting database.user for application " + app + " in environment prod\"}");

      assertThat(send("GET", settingsPath() + "/a..b", token()).statusCode()).isEqualTo(400);
    }

    @Test
    void headOnOneKeyHasTheHeadersGetWouldSend() throws Exception {
      settings(Map.of("database.url", "jdbc:postgresql://db:5432/billing"));

      final HttpResponse<String> get = send("GET", settingsPath() + "/database.url", token());
      final HttpResponse<String> head = send("HEAD", settingsPath() + "/database.url", token());

      assertThat(head.statusCode()).isEqualTo(200);
      assertThat(head.body()).isEmpty();
      for (final String header : new String[] {"ETag", "Config-Version", "Cache-Control", "Content-Length"}) {
        assertThat(head.headers().firstValue(header)).as(header).isEqualTo(get.headers().firstValue(header));
      }
    }

    @Test
    void metadataCannotBeReadThroughTheKeyEndpoint() throws Exception {
      settings(Map.of("a", "1"));

      final HttpResponse<String> response = send("GET", settingsPath() + "/auth.test.subjects", token());

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body()).doesNotContain(SUBJECT);
    }

    @Test
    void metadataSetsTheCacheHeader() throws Exception {
      settings(Map.of("a", "1"));
      final SetId metadata = SetId.metadata(application(), environment());
      datastore().put(metadata, Versions.ANY, Key.from("cache.max-age"), new Value("120"));
      datastore().put(metadata, Versions.ANY, Key.from("cache.stale-if-error"), new Value("600"));

      assertThat(send("GET", settingsPath(), token()).headers().firstValue("Cache-Control"))
          .contains("private, max-age=120, stale-while-revalidate=120, stale-if-error=600");
    }

    @Test
    void metadataIsNeverServed() throws Exception {
      settings(Map.of("a", "1"));
      datastore().put(SetId.metadata(application(), environment()), Versions.ANY,
          Key.from("cache.max-age"), new Value("45"));

      final HttpResponse<String> response = send("GET", settingsPath(), token());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("{\"application\":\"" + app + "\",\"environment\":\"prod\","
          + "\"version\":1,\"values\":{\"a\":\"1\"}}");
    }
  }

  @Nested
  class ReadOnly {

    @Test
    void writesAreNotAllowed() throws Exception {
      grant(SUBJECT);
      for (final String method : new String[] {"PUT", "POST", "PATCH", "DELETE"}) {
        final HttpResponse<String> response = send(method, settingsPath(), token());
        assertThat(response.statusCode()).as(method).isEqualTo(405);
        assertThat(response.body()).as(method).isEqualTo("{\"message\":\"Method Not Allowed\"}");
      }
    }

    @Test
    void anUnacceptableMediaTypeIs406WithAJsonBody() throws Exception {
      grant(SUBJECT);
      settings(Map.of("a", "1"));

      final HttpResponse<String> response = send("GET", settingsPath(), token(), "Accept", "text/plain");

      assertThat(response.statusCode()).isEqualTo(406);
      assertThat(response.body()).isEqualTo("{\"message\":\"Not Acceptable\"}");
    }

    @Test
    void pathsJettyRefusesStillGetAJsonBody() throws Exception {
      final HttpResponse<String> response =
          send("GET", "/v1/applications/a%2Fb/environments/prod/settings", token());

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
          type -> assertThat(type).startsWith("application/json"));
      assertThat(response.body()).isEqualTo("{\"message\":\"Bad Request\"}");
    }

    @Test
    void unknownPathsAre404() throws Exception {
      final HttpResponse<String> response = send("GET", "/v1/applications", token());

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body()).isEqualTo("{\"message\":\"Not Found\"}");
    }
  }

  private Application application() {
    return Application.from(app);
  }

  private static Environment environment() {
    return Environment.from("prod");
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

  /** Lets subjects read this test's application, the way an operator would: through the library. */
  private void grant(final String... subjects) {
    datastore().put(SetId.metadata(application(), environment()), Versions.ANY,
        Key.from("auth.test.subjects"), new Value(String.join(" ", subjects)));
  }

  private void settings(final Map<String, String> values) {
    final Map<Key, Value> converted = new LinkedHashMap<>();
    values.forEach((key, value) -> converted.put(Key.from(key), new Value(value)));
    datastore().replace(SetId.settings(application(), environment()), Versions.ANY, converted);
  }

  private static HttpResponse<String> send(final String method, final String path, final String token,
                                           final String... headers) throws Exception {
    final HttpRequest.Builder request = HttpRequest
        .newBuilder(URI.create("http://localhost:" + APP.getLocalPort() + path))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> sendUnchecked(final String method, final String path, final String token) {
    try {
      return send(method, path, token);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
