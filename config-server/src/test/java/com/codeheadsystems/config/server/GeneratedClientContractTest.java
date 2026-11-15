package com.codeheadsystems.config.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.client.api.SettingsApi;
import com.codeheadsystems.config.client.invoker.ApiClient;
import com.codeheadsystems.config.client.invoker.ApiException;
import com.codeheadsystems.config.client.invoker.ApiResponse;
import com.codeheadsystems.config.client.model.ErrorMessage;
import com.codeheadsystems.config.client.model.Setting;
import com.codeheadsystems.config.client.model.Settings;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The server, called through the Java client generated from {@code openapi/config-api.yaml}: what
 * the spec promises is what a generated client gets. The generated client throws
 * {@link ApiException} for any status but 2xx, including {@code 304}.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class GeneratedClientContractTest {

  private static final TestOidcIssuer ISSUER = TestOidcIssuer.start();
  private static final String SUBJECT = "system:serviceaccount:billing:billing";
  private static final AtomicInteger APPS = new AtomicInteger();

  private static final DropwizardAppExtension<ConfigServerConfiguration> APP = new DropwizardAppExtension<>(
      ConfigServerApplication.class,
      ResourceHelpers.resourceFilePath("test-config.yml"),
      ConfigOverride.config("auth.issuers[0].issuer", () -> ISSUER.issuer().toString()));

  private String app;

  @BeforeEach
  void newApplication() {
    app = "client-app-" + APPS.incrementAndGet();
  }

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Test
  void getSettingsReturnsTheModelAndVersionHeaders() throws Exception {
    grant();
    settings(Map.of("database.url", "jdbc:postgresql://db:5432/billing", "database.pool.size", "10"));

    final ApiResponse<Settings> response = api(token()).getSettingsWithHttpInfo(app, "prod", null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    final Settings settings = response.getData();
    assertThat(settings.getApplication()).isEqualTo(app);
    assertThat(settings.getEnvironment()).isEqualTo("prod");
    assertThat(settings.getVersion()).isEqualTo(1L);
    assertThat(settings.getValues()).containsExactlyInAnyOrderEntriesOf(
        Map.of("database.url", "jdbc:postgresql://db:5432/billing", "database.pool.size", "10"));
    assertThat(response.getHeaders()).containsEntry("etag", List.of("\"1\""))
        .containsEntry("config-version", List.of("1"))
        .containsKey("cache-control");
  }

  @Test
  void anUnchangedVersionIs304ThroughTheClient() throws Exception {
    grant();
    settings(Map.of("a", "1"));

    assertThatThrownBy(() -> api(token()).getSettingsWithHttpInfo(app, "prod", "\"1\""))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.getCode()).isEqualTo(304);
          assertThat(e.getResponseHeaders().firstValue("Config-Version")).contains("1");
        });
  }

  @Test
  void headReturnsOnlyTheVersion() throws Exception {
    grant();
    settings(Map.of("a", "1"));

    final ApiResponse<Void> response = api(token()).getSettingsVersionWithHttpInfo(app, "prod", null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders()).containsEntry("config-version", List.of("1"));
    assertThatThrownBy(() -> api(token()).getSettingsVersionWithHttpInfo(app, "prod", "\"1\""))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(304));
  }

  @Test
  void getSettingReturnsOneValue() throws Exception {
    grant();
    settings(Map.of("database.url", "jdbc:postgresql://db:5432/billing"));

    final Setting setting = api(token()).getSetting(app, "prod", "database.url");

    assertThat(setting.getKey()).isEqualTo("database.url");
    assertThat(setting.getValue()).isEqualTo("jdbc:postgresql://db:5432/billing");
  }

  @Test
  void errorsHaveTheErrorMessageModel() throws Exception {
    settings(Map.of("a", "1"));

    assertError(() -> api(null).getSettings(app, "prod", null), 401, "A bearer token is required");
    assertError(() -> api(token()).getSettings("Billing", "prod", null), 400, "Invalid application 'Billing'");
    assertError(() -> api(token()).getSettings(app, "prod", null), 403,
        "Not allowed to read application " + app + " in environment prod");
    grant();
    assertError(() -> api(token()).getSetting(app, "prod", "missing"), 404,
        "No setting missing for application " + app + " in environment prod");
  }

  private interface Call {
    void run() throws ApiException;
  }

  private static void assertError(final Call call, final int status, final String message) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.getCode()).isEqualTo(status);
      try {
        assertThat(new ApiClient().getObjectMapper().readValue(e.getResponseBody(), ErrorMessage.class).getMessage())
            .isEqualTo(message);
      } catch (final Exception parse) {
        throw new AssertionError("Error body is not the ErrorMessage model: " + e.getResponseBody(), parse);
      }
    });
  }

  /** The generated client, pointed at the test server, sending a bearer token if given. */
  private static SettingsApi api(final String token) {
    final ApiClient client = new ApiClient();
    client.updateBaseUri("http://localhost:" + APP.getLocalPort());
    if (token != null) {
      client.setRequestInterceptor(request -> request.header("Authorization", "Bearer " + token));
    }
    return new SettingsApi(client);
  }

  private static String token() {
    return ISSUER.token(SUBJECT).sign();
  }

  private void grant() {
    ((ConfigServerApplication) APP.getApplication()).datastore().put(
        SetId.metadata(Application.from(app), Environment.from("prod")), Versions.ANY,
        Key.from("auth.test.subjects"), new Value(SUBJECT));
  }

  private void settings(final Map<String, String> values) {
    final Map<Key, Value> converted = new LinkedHashMap<>();
    values.forEach((key, value) -> converted.put(Key.from(key), new Value(value)));
    ((ConfigServerApplication) APP.getApplication()).datastore().replace(
        SetId.settings(Application.from(app), Environment.from("prod")), Versions.ANY, converted);
  }
}
