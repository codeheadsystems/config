package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfigurationBuilderTest {

  private static final Key TIMEOUT = Key.from("timeout");

  @Nested
  class Builder {

    @Test
    void buildWithoutASourceIsRejected() {
      assertThatThrownBy(() -> Configuration.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no configuration source set");
    }

    @Test
    void withNullFunctionIsRejected() {
      assertThatThrownBy(() -> Configuration.builder().with((Function<Key, Optional<Value>>) null))
        .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withFunctionUsesTheFunction() {
      final Configuration configuration = Configuration.builder()
        .with(key -> Optional.of(new Value(key.name().toUpperCase())))
        .build();

      assertThat(configuration.get("timeout")).isEqualTo(new Value("TIMEOUT"));
    }

    @Test
    void firstSourceWins() {
      final Configuration configuration = Configuration.builder()
        .with(Map.of("timeout", "30s"))
        .with(Map.of("timeout", "10s", "retries", "3"))
        .build();

      assertThat(configuration.find("timeout")).contains(new Value("30s"));
      assertThat(configuration.find("retries")).contains(new Value("3"));
    }

    @Test
    void builderChangesAfterBuildDoNotAffectTheBuiltConfiguration() {
      final Configuration.Builder builder = Configuration.builder()
        .with(Map.of("timeout", "30s"))
        .with(Map.of("retries", "3"));
      final Configuration configuration = builder.build();

      builder.with(Map.of("region", "us-west-2"));

      assertThat(configuration.find("region")).isEmpty();
      assertThat(builder.build().find("region")).contains(new Value("us-west-2"));
    }

    @Test
    void withNullConfigurationIsRejected() {
      assertThatThrownBy(() -> Configuration.builder().with((Configuration) null))
        .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromWrapsAFunction() {
      final Configuration configuration = Configuration.from(key -> Value.from("30s"));
      assertThat(configuration.get(TIMEOUT)).isEqualTo(new Value("30s"));
    }
  }

  @Nested
  class NullFunction {

    private final Configuration configuration = Configuration.builder().withNullFunction().build();

    @Test
    void findIsAlwaysEmpty() {
      assertThat(configuration.find("timeout")).isEmpty();
      assertThat(configuration.find(Key.from("database.url"))).isEmpty();
    }

    @Test
    void getThrowsNamingTheKey() {
      assertThatThrownBy(() -> configuration.get("timeout"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Configuration not found: timeout");
    }

    @Test
    void namespaceIsAlsoEmpty() {
      assertThat(configuration.withNamespace("database").find("url")).isEmpty();
    }

    @Test
    void stillValidatesKeys() {
      assertThatThrownBy(() -> configuration.find("a b"))
        .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class FromMap {

    private final Configuration configuration = Configuration.builder()
      .with(Map.of(
        "timeout", "30s",
        "database.url", "jdbc:postgresql://db/config",
        "empty", ""))
      .build();

    @Test
    void findReturnsPresentValue() {
      assertThat(configuration.find("timeout")).contains(new Value("30s"));
    }

    @Test
    void findMatchesTheFullDottedKey() {
      assertThat(configuration.find(Key.from("database.url")))
        .contains(new Value("jdbc:postgresql://db/config"));
      assertThat(configuration.find("database")).isEmpty();
    }

    @Test
    void findReturnsEmptyForMissingKey() {
      assertThat(configuration.find("missing")).isEmpty();
    }

    @Test
    void emptyStringIsAValue() {
      assertThat(configuration.find("empty")).contains(new Value(""));
    }

    @Test
    void getThrowsForMissingKey() {
      assertThatThrownBy(() -> configuration.get("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Configuration not found: missing");
    }

    @Test
    void namespaceResolvesAgainstTheMap() {
      assertThat(configuration.withNamespace("database").get("url"))
        .isEqualTo(new Value("jdbc:postgresql://db/config"));
    }

    @Test
    void nullValueInMapIsTreatedAsMissing() {
      final Map<String, String> map = new HashMap<>();
      map.put("timeout", null);
      assertThat(Configuration.builder().with(map).build().find("timeout")).isEmpty();
    }

    // The map is not copied; immutability is the caller's choice.
    @Test
    void reflectsChangesToTheBackingMap() {
      final Map<String, String> map = new HashMap<>();
      final Configuration live = Configuration.builder().with(map).build();
      assertThat(live.find("timeout")).isEmpty();

      map.put("timeout", "45s");

      assertThat(live.find("timeout")).contains(new Value("45s"));
    }

    @Test
    void rejectsNullMap() {
      assertThatThrownBy(() -> Configuration.builder().with((Map<String, String>) null))
        .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class FromProperties {

    private Properties properties;
    private Configuration configuration;

    @BeforeEach
    void setUp() throws IOException {
      properties = new Properties();
      properties.load(new StringReader("""
        timeout=30s
        database.url=jdbc:postgresql://db/config
        empty=
        """));
      configuration = Configuration.builder().with(properties).build();
    }

    @Test
    void findReturnsPresentValue() {
      assertThat(configuration.find("timeout")).contains(new Value("30s"));
    }

    @Test
    void findMatchesTheFullDottedKey() {
      assertThat(configuration.find(Key.from("database.url")))
        .contains(new Value("jdbc:postgresql://db/config"));
      assertThat(configuration.find("database")).isEmpty();
    }

    @Test
    void findReturnsEmptyForMissingKey() {
      assertThat(configuration.find("missing")).isEmpty();
    }

    @Test
    void emptyStringIsAValue() {
      assertThat(configuration.find("empty")).contains(new Value(""));
    }

    @Test
    void getThrowsForMissingKey() {
      assertThatThrownBy(() -> configuration.get("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Configuration not found: missing");
    }

    @Test
    void namespaceResolvesAgainstTheProperties() {
      assertThat(configuration.withNamespace("database").get("url"))
        .isEqualTo(new Value("jdbc:postgresql://db/config"));
    }

    @Test
    void findsValuesFromThePropertiesDefaults() {
      final Properties defaults = new Properties();
      defaults.setProperty("retries", "3");
      defaults.setProperty("timeout", "10s");
      final Properties withDefaults = new Properties(defaults);
      withDefaults.setProperty("timeout", "30s");

      final Configuration config = Configuration.builder().with(withDefaults).build();

      assertThat(config.find("retries")).contains(new Value("3"));
      assertThat(config.find("timeout")).contains(new Value("30s"));
    }

    @Test
    void nonStringValuesAreTreatedAsMissing() {
      properties.put("count", 42);
      assertThat(configuration.find("count")).isEmpty();
    }

    // The properties are not copied; immutability is the caller's choice.
    @Test
    void reflectsChangesToTheBackingProperties() {
      properties.setProperty("timeout", "45s");
      assertThat(configuration.find("timeout")).contains(new Value("45s"));
    }

    @Test
    void rejectsNullProperties() {
      assertThatThrownBy(() -> Configuration.builder().with((Properties) null))
        .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Fallback {

    private final Configuration main = Configuration.builder()
      .with(Map.of(
        "timeout", "30s",
        "database.url", "jdbc:postgresql://main/config"))
      .build();
    private final Configuration fallback = Configuration.builder()
      .with(Map.of(
        "timeout", "10s",
        "retries", "3",
        "database.pool.size", "5"))
      .build();
    private final Configuration configuration = fallback(main, fallback);

    private Configuration fallback(final Configuration first, final Configuration second) {
      return Configuration.builder().with(first).with(second).build();
    }

    @Test
    void mainValueWinsOverFallback() {
      assertThat(configuration.find(TIMEOUT)).contains(new Value("30s"));
    }

    @Test
    void fallbackIsUsedWhenMainIsMissingTheKey() {
      assertThat(configuration.find("retries")).contains(new Value("3"));
    }

    @Test
    void findIsEmptyWhenNeitherHasTheKey() {
      assertThat(configuration.find("missing")).isEmpty();
    }

    @Test
    void getThrowsWhenNeitherHasTheKey() {
      assertThatThrownBy(() -> configuration.get("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Configuration not found: missing");
    }

    @Test
    void namespaceOverAFallbackReportsTheFullKey() {
      assertThatThrownBy(() -> configuration.withNamespace("database").get("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Configuration not found: database.missing");
    }

    @Test
    void namespaceFallsBackPerKey() {
      final Configuration database = configuration.withNamespace("database");
      assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://main/config"));
      assertThat(database.get("pool.size")).isEqualTo(new Value("5"));
    }

    @Test
    void namespacedConfigurationsCanBeCombined() {
      final Configuration combined = fallback(
        main.withNamespace("database"), fallback.withNamespace("database"));

      assertThat(combined.get("url")).isEqualTo(new Value("jdbc:postgresql://main/config"));
      assertThat(combined.get("pool.size")).isEqualTo(new Value("5"));
    }

    @Test
    void fallbackIsNotConsultedWhenMainHasTheKey() {
      final List<Key> fallbackLookups = new ArrayList<>();
      final Configuration recordingFallback = Configuration.from(key -> {
        fallbackLookups.add(key);
        return Optional.empty();
      });

      assertThat(fallback(main, recordingFallback).find(TIMEOUT)).contains(new Value("30s"));
      assertThat(fallbackLookups).isEmpty();
    }

    @Test
    void fallbackIsConsultedWithTheSameKey() {
      final List<Key> fallbackLookups = new ArrayList<>();
      final Configuration recordingFallback = Configuration.from(key -> {
        fallbackLookups.add(key);
        return Value.from("10s");
      });

      assertThat(fallback(main, recordingFallback).find("retries")).contains(new Value("10s"));
      assertThat(fallbackLookups).containsExactly(Key.from("retries"));
    }

    @Test
    void chainsThroughManySources() {
      final Properties properties = new Properties();
      properties.setProperty("region", "us-west-2");
      properties.setProperty("retries", "99");

      final Configuration chained = Configuration.builder()
        .with(main)
        .with(fallback)
        .with(properties)
        .with(key -> Value.from(key.name().equals("zone") ? "a" : null))
        .build();

      assertThat(chained.get(TIMEOUT)).isEqualTo(new Value("30s"));
      assertThat(chained.get("retries")).isEqualTo(new Value("3"));
      assertThat(chained.get("region")).isEqualTo(new Value("us-west-2"));
      assertThat(chained.get("zone")).isEqualTo(new Value("a"));
      assertThat(chained.find("missing")).isEmpty();
    }

    @Test
    void stopsAtTheFirstSourceWithTheKey() {
      final List<String> asked = new ArrayList<>();
      final Configuration configuration = Configuration.builder()
        .with(key -> {
          asked.add("first");
          return Optional.empty();
        })
        .with(key -> {
          asked.add("second");
          return Value.from("found");
        })
        .with(key -> {
          asked.add("third");
          return Value.from("unreached");
        })
        .build();

      assertThat(configuration.get(TIMEOUT)).isEqualTo(new Value("found"));
      assertThat(asked).containsExactly("first", "second");
    }

    @Test
    void nestedBuiltConfigurationsCanBeSources() {
      final Configuration last = Configuration.builder().with(Map.of("region", "us-west-2")).build();
      final Configuration chained = fallback(main, fallback(fallback, last));

      assertThat(chained.get(TIMEOUT)).isEqualTo(new Value("30s"));
      assertThat(chained.get("retries")).isEqualTo(new Value("3"));
      assertThat(chained.get("region")).isEqualTo(new Value("us-west-2"));
    }

    @Test
    void nullFunctionAsFallbackAddsNothing() {
      final Configuration withNull = fallback(main, Configuration.builder().withNullFunction().build());
      assertThat(withNull.find(TIMEOUT)).contains(new Value("30s"));
      assertThat(withNull.find("retries")).isEmpty();
    }
  }
}
