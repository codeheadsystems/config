package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigurationFactoryTest {

  private static final Application BILLING = Application.from("billing");
  private static final Application SHIPPING = Application.from("shipping");
  private static final Environment PROD = Environment.from("prod");
  private static final Environment DEV = Environment.from("dev");

  private final InMemoryConfigurationFactory.Store store = new InMemoryConfigurationFactory.Store()
    .put(BILLING, PROD, Map.of("database.url", "jdbc:postgresql://prod/billing", "timeout", "30s"))
    .put(BILLING, DEV, Map.of("database.url", "jdbc:postgresql://dev/billing"))
    .put(SHIPPING, PROD, Map.of("database.url", "jdbc:postgresql://prod/shipping"));

  private Configuration configurationFor(final Application application, final Environment environment) {
    return InMemoryConfigurationFactory.builder()
      .with(application)
      .with(environment)
      .with(store)
      .build()
      .build();
  }

  // The factory

  @Test
  void factoryBuildsTheConfigurationForItsApplicationAndEnvironment() {
    final ConfigurationFactory factory = InMemoryConfigurationFactory.builder()
      .with(BILLING)
      .with(PROD)
      .with(store)
      .build();

    final Configuration configuration = factory.build();

    assertThat(configuration.get("database.url")).isEqualTo(new Value("jdbc:postgresql://prod/billing"));
    assertThat(configuration.get("timeout")).isEqualTo(new Value("30s"));
  }

  @Test
  void environmentsAreKeptApart() {
    final Configuration dev = configurationFor(BILLING, DEV);

    assertThat(dev.get("database.url")).isEqualTo(new Value("jdbc:postgresql://dev/billing"));
    assertThat(dev.find("timeout")).isEmpty();
  }

  @Test
  void applicationsAreKeptApart() {
    final Configuration shipping = configurationFor(SHIPPING, PROD);

    assertThat(shipping.get("database.url")).isEqualTo(new Value("jdbc:postgresql://prod/shipping"));
    assertThat(shipping.find("timeout")).isEmpty();
  }

  @Test
  void configurationSupportsNamespaces() {
    final Configuration database = configurationFor(BILLING, PROD).withNamespace("database");

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://prod/billing"));
  }

  @Test
  void factoryCanBuildMoreThanOnce() {
    final ConfigurationFactory factory = InMemoryConfigurationFactory.builder()
      .with(BILLING).with(PROD).with(store).build();

    assertThat(factory.build().get("timeout")).isEqualTo(factory.build().get("timeout"));
  }

  // The test backend's choice; each backend decides what an unknown application or environment means.
  @Test
  void testBackendGivesAnEmptyConfigurationForUnknownCoordinates() {
    assertThat(configurationFor(Application.from("unknown"), PROD).find("database.url")).isEmpty();
  }

  // The base builder

  @Test
  void backendMethodsChainWithTheBaseWithMethodsInAnyOrder() {
    final ConfigurationFactory factory = InMemoryConfigurationFactory.builder()
      .with(store)
      .with(PROD)
      .with(BILLING)
      .build();

    assertThat(factory.build().get("timeout")).isEqualTo(new Value("30s"));
  }

  @Test
  void laterWithReplacesTheEarlierValue() {
    final ConfigurationFactory factory = InMemoryConfigurationFactory.builder()
      .with(SHIPPING)
      .with(DEV)
      .with(BILLING)
      .with(PROD)
      .with(store)
      .build();

    assertThat(factory.build().get("database.url")).isEqualTo(new Value("jdbc:postgresql://prod/billing"));
  }

  @Test
  void baseBuilderPassesTheApplicationAndEnvironmentToTheBackend() {
    final List<Object> received = new ArrayList<>();

    new RecordingBuilder(received).with(BILLING).with(PROD).build();

    assertThat(received).containsExactly(BILLING, PROD);
  }

  @Test
  void eachBuildCallsTheBackendAgain() {
    final List<Object> received = new ArrayList<>();
    final RecordingBuilder builder = new RecordingBuilder(received).with(BILLING).with(PROD);

    builder.build();
    builder.with(DEV).build();

    assertThat(received).containsExactly(BILLING, PROD, BILLING, DEV);
  }

  @Test
  void buildWithoutApplicationIsRejectedBeforeTheBackendIsCalled() {
    final List<Object> received = new ArrayList<>();

    assertThatThrownBy(() -> new RecordingBuilder(received).with(PROD).build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("no application set");
    assertThat(received).isEmpty();
  }

  @Test
  void buildWithoutEnvironmentIsRejectedBeforeTheBackendIsCalled() {
    final List<Object> received = new ArrayList<>();

    assertThatThrownBy(() -> new RecordingBuilder(received).with(BILLING).build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("no environment set");
    assertThat(received).isEmpty();
  }

  @Test
  void nullApplicationIsRejected() {
    assertThatThrownBy(() -> InMemoryConfigurationFactory.builder().with((Application) null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("application must not be null");
  }

  @Test
  void nullEnvironmentIsRejected() {
    assertThatThrownBy(() -> InMemoryConfigurationFactory.builder().with((Environment) null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("environment must not be null");
  }

  // Backend-specific settings

  @Test
  void backendValidatesItsOwnSettings() {
    assertThatThrownBy(() -> InMemoryConfigurationFactory.builder().with(BILLING).with(PROD).build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("no store set");
  }

  @Test
  void backendRejectsNullSettings() {
    assertThatThrownBy(() -> InMemoryConfigurationFactory.builder().with((InMemoryConfigurationFactory.Store) null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("store must not be null");
  }

  /** A minimal backend builder that records what the base builder hands it. */
  private static final class RecordingBuilder extends ConfigurationFactory.Builder<RecordingBuilder> {

    private final List<Object> received;

    RecordingBuilder(final List<Object> received) {
      this.received = received;
    }

    @Override
    protected RecordingBuilder self() {
      return this;
    }

    @Override
    protected ConfigurationFactory build(final Application application, final Environment environment) {
      received.add(application);
      received.add(environment);
      return () -> Configuration.builder().withNullFunction().build();
    }
  }
}
