package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigurationTest {

  private RecordingFunction lookups;
  private Configuration root;

  @BeforeEach
  void setUp() {
    lookups = new RecordingFunction(Map.of(
      "timeout", "30s",
      "database.url", "jdbc:postgresql://db/config",
      "database.pool.size", "10"));
    root = Configuration.from(lookups);
  }

  // find / get with a String

  @Test
  void findStringReturnsPresentValue() {
    assertThat(root.find("timeout")).contains(new Value("30s"));
  }

  @Test
  void findStringReturnsEmptyForMissingKey() {
    assertThat(root.find("missing")).isEmpty();
  }

  @Test
  void findStringAcceptsDottedKey() {
    assertThat(root.find("database.url")).contains(new Value("jdbc:postgresql://db/config"));
  }

  @Test
  void getStringReturnsPresentValue() {
    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
  }

  @Test
  void getStringThrowsForMissingKeyNamingIt() {
    assertThatThrownBy(() -> root.get("missing"))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: missing");
  }

  @Test
  void findStringValidatesTheKeyBeforeTheDatastoreSeesIt() {
    assertThatThrownBy(() -> root.find("a b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.find(".url")).isInstanceOf(IllegalArgumentException.class);
    assertThat(lookups.requested).isEmpty();
  }

  @Test
  void getStringValidatesTheKeyBeforeTheDatastoreSeesIt() {
    assertThatThrownBy(() -> root.get("url.")).isInstanceOf(IllegalArgumentException.class);
    assertThat(lookups.requested).isEmpty();
  }

  @Test
  void stringAndKeyFormsAgree() {
    assertThat(root.find("database.url")).isEqualTo(root.find(Key.from("database.url")));
    assertThat(root.get("timeout")).isEqualTo(root.get(Key.from("timeout")));
  }

  @Test
  void nullStringKeyIsRejected() {
    assertThatThrownBy(() -> root.find((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.get((String) null)).isInstanceOf(NullPointerException.class);
  }

  // find / get with a Key

  @Test
  void findKeyReturnsPresentValue() {
    assertThat(root.find(Key.from("timeout"))).contains(new Value("30s"));
  }

  @Test
  void getKeyThrowsForMissingKeyNamingIt() {
    assertThatThrownBy(() -> root.get(Key.from("missing")))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: missing");
  }

  // namespaces

  @Test
  void withNamespaceStringPrefixesLookups() {
    final Configuration database = root.withNamespace("database");

    assertThat(database.find("url")).contains(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withNamespaceKeyPrefixesLookups() {
    final Configuration database = root.withNamespace(Key.from("database"));

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withMultiSegmentNamespace() {
    final Configuration pool = root.withNamespace(Key.from("database").and("pool"));

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void withDottedNamespaceString() {
    final Configuration pool = root.withNamespace("database.pool");

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void namespacedViewAcceptsDottedKey() {
    final Configuration database = root.withNamespace("database");

    assertThat(database.get("pool.size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithNamespaceCombinesNamespacesForLookups() {
    final Configuration pool = root.withNamespace("database").withNamespace("pool");

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithNamespaceMakesOneLookupWithTheFullKey() {
    final Configuration pool = root.withNamespace("database").withNamespace("pool");

    assertThat(pool.find("size")).contains(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void namespacedViewsCanBeUsedAsFallbackSources() {
    final Configuration database = Configuration.builder()
      .with(root.withNamespace("database"))
      .with(root.withNamespace("database.pool"))
      .build();

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://db/config"));
    assertThat(database.get("size")).isEqualTo(new Value("10"));
  }

  @Test
  void nestedWithNamespaceMatchesSingleCombinedNamespace() {
    final Configuration nested = root.withNamespace("database").withNamespace("pool");
    final Configuration combined = root.withNamespace(Key.from("database").and("pool"));

    assertThat(nested.find("size")).isEqualTo(combined.find("size"));
  }

  @Test
  void namespacedViewDoesNotSeeKeysOutsideIt() {
    final Configuration database = root.withNamespace("database");

    assertThat(database.find("timeout")).isEmpty();
    assertThat(lookups.requested).containsExactly("database.timeout");
  }

  @Test
  void namespacedGetReportsTheFullKey() {
    final Configuration database = root.withNamespace("database");

    assertThatThrownBy(() -> database.get("missing"))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: database.missing");
  }

  @Test
  void nestedNamespacedGetReportsTheFullKey() {
    final Configuration pool = root.withNamespace("database").withNamespace("pool");

    assertThatThrownBy(() -> pool.get(Key.from("max")))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: database.pool.max");
  }

  @Test
  void namespacedViewValidatesTheKey() {
    final Configuration database = root.withNamespace("database");

    assertThatThrownBy(() -> database.find(".url")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> database.get("")).isInstanceOf(IllegalArgumentException.class);
    assertThat(lookups.requested).isEmpty();
  }

  @Test
  void withNamespaceDoesNotChangeTheParent() {
    root.withNamespace("database");

    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
    assertThat(lookups.requested).containsExactly("timeout");
  }

  @Test
  void withNamespaceRejectsInvalidNamespace() {
    assertThatThrownBy(() -> root.withNamespace(".database")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.withNamespace("database.")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.withNamespace("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.withNamespace("data base")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullsAreRejected() {
    assertThatThrownBy(() -> Configuration.from(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("function must not be null");
    assertThatThrownBy(() -> root.find((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.withNamespace("database").get((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.withNamespace((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.withNamespace((String) null))
      .isInstanceOf(NullPointerException.class);
  }

  /**
   * A map-backed lookup function that records the name of every key it is asked for, so tests can
   * assert the exact key a namespaced view resolves to.
   */
  private static final class RecordingFunction implements Function<Key, Optional<Value>> {

    private final Map<String, String> values;
    private final List<String> requested = new ArrayList<>();

    RecordingFunction(final Map<String, String> values) {
      this.values = values;
    }

    @Override
    public Optional<Value> apply(final Key key) {
      requested.add(key.name());
      return Value.from(values.get(key.name()));
    }
  }
}
