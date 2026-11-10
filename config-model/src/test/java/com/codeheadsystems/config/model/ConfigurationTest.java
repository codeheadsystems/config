package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

  private RecordingConfiguration root;

  @BeforeEach
  void setUp() {
    root = new RecordingConfiguration(Map.of(
        "timeout", "30s",
        "database.url", "jdbc:postgresql://db/config",
        "database.pool.size", "10"));
  }

  @Test
  void findReturnsPresentValue() {
    assertThat(root.find("timeout")).contains(new Value("30s"));
  }

  @Test
  void findReturnsEmptyForMissingKey() {
    assertThat(root.find("missing")).isEmpty();
  }

  @Test
  void getReturnsPresentValue() {
    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
  }

  @Test
  void getThrowsForMissingKeyNamingIt() {
    assertThatThrownBy(() -> root.get("missing"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void withStringPrefixesLookups() {
    final Configuration database = root.with("database");

    assertThat(database.find("url")).contains(new Value("jdbc:postgresql://db/config"));
    assertThat(root.requested).containsExactly("database.url");
  }

  @Test
  void withNamespacePrefixesLookups() {
    final Configuration database = root.with(Namespace.from("database"));

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://db/config"));
    assertThat(root.requested).containsExactly("database.url");
  }

  @Test
  void withMultiSegmentNamespace() {
    final Configuration pool = root.with(Namespace.from("database").and("pool"));

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(root.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithCombinesNamespacesForLookups() {
    final Configuration pool = root.with("database").with("pool");

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(root.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithMatchesSingleCombinedNamespace() {
    final Configuration nested = root.with("database").with("pool");
    final Configuration combined = root.with(Namespace.from("database").and("pool"));

    assertThat(nested.find("size")).isEqualTo(combined.find("size"));
  }

  @Test
  void namespacedViewDoesNotSeeKeysOutsideIt() {
    final Configuration database = root.with("database");

    assertThat(database.find("timeout")).isEmpty();
    assertThat(root.requested).containsExactly("database.timeout");
  }

  @Test
  void namespacedGetThrowsForMissingKey() {
    final Configuration database = root.with("database");

    assertThatThrownBy(() -> database.get("missing")).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void withDoesNotChangeTheParent() {
    root.with("database");

    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
    assertThat(root.requested).containsExactly("timeout");
  }

  @Test
  void configurationNamespaceRejectsNulls() {
    final Namespace namespace = Namespace.from("database");

    assertThatThrownBy(() -> new Configuration.ConfigurationNamespace(null, namespace))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Configuration.ConfigurationNamespace(root, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with((Namespace) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * A map-backed configuration that records every key it is asked for, so tests can assert the
   * exact key a namespaced view resolves to.
   */
  private static final class RecordingConfiguration implements Configuration {

    private final Map<String, String> values;
    private final List<String> requested = new ArrayList<>();

    RecordingConfiguration(final Map<String, String> values) {
      this.values = values;
    }

    @Override
    public Optional<Value> find(final String name) {
      requested.add(name);
      return Value.from(values.get(name));
    }
  }
}
