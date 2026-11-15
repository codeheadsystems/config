package com.codeheadsystems.config.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.client.ClientConfigurationFactory;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.testoidc.TestOidcIssuer;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The client's ConfigurationFactory against the real server, tokens and all. */
@ExtendWith(DropwizardExtensionsSupport.class)
class ClientConfigurationFactoryEndToEndTest {

  private static final TestOidcIssuer ISSUER = TestOidcIssuer.start();
  private static final String SUBJECT = "system:serviceaccount:billing:billing";
  private static final Application APP = Application.from("e2e-billing");
  private static final Environment ENV = Environment.from("prod");

  private static final DropwizardAppExtension<ConfigServerConfiguration> SERVER = new DropwizardAppExtension<>(
      ConfigServerApplication.class,
      ResourceHelpers.resourceFilePath("test-config.yml"),
      ConfigOverride.config("auth.issuers[0].issuer", () -> ISSUER.issuer().toString()));

  @AfterAll
  static void stopIssuer() {
    ISSUER.close();
  }

  @Test
  void followsTheSettingsThroughChangesAndKeepsThemWhenAccessIsRevoked() {
    grant(SUBJECT);
    settings(Map.of("database.url", "jdbc:postgresql://db/billing", "pool", "10"));

    try (ClientConfigurationFactory factory = ClientConfigurationFactory.builder()
        .with(APP)
        .with(ENV)
        .baseUri(URI.create("http://localhost:" + SERVER.getLocalPort()))
        .token(() -> ISSUER.token(SUBJECT).sign())
        .autoRefresh(false)
        .requireSettingsAtStartup(true)
        .build()) {
      final Configuration configuration = factory.configuration();
      assertThat(factory.settingsVersion()).isEqualTo(1);
      assertThat(configuration.with("database").get("url").value()).isEqualTo("jdbc:postgresql://db/billing");

      // Unchanged: the server answers 304.
      assertThat(factory.refresh()).isFalse();
      assertThat(factory.problem()).isEmpty();

      settings(Map.of("database.url", "jdbc:postgresql://db/billing", "pool", "20"));
      assertThat(factory.refresh()).isTrue();
      assertThat(factory.settingsVersion()).isEqualTo(2);
      assertThat(configuration.get("pool").value()).isEqualTo("20");

      grant("someone-else");
      assertThat(factory.refresh()).isFalse();
      assertThat(factory.problem())
          .contains("403: Not allowed to read application e2e-billing in environment prod");
      assertThat(configuration.get("pool").value()).isEqualTo("20");

      grant(SUBJECT);
      factory.refresh();
      assertThat(factory.problem()).isEmpty();
    }
  }

  private static ConfigServerApplication application() {
    return (ConfigServerApplication) SERVER.getApplication();
  }

  private static void grant(final String subject) {
    application().datastore().put(SetId.metadata(APP, ENV), Versions.ANY, Key.from("auth.test.subjects"),
        new Value(subject));
  }

  private static void settings(final Map<String, String> values) {
    final Map<Key, Value> converted = new LinkedHashMap<>();
    values.forEach((key, value) -> converted.put(Key.from(key), new Value(value)));
    application().datastore().replace(SetId.settings(APP, ENV), Versions.ANY, converted);
  }
}
