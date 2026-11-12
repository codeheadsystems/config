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
  void withStringPrefixesLookups() {
    final Configuration database = root.with("database");

    assertThat(database.find("url")).contains(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withKeyPrefixesLookups() {
    final Configuration database = root.with(Key.from("database"));

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withMultiSegmentNamespace() {
    final Configuration pool = root.with(Key.from("database").and("pool"));

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void withDottedNamespaceString() {
    final Configuration pool = root.with("database.pool");

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void namespacedViewAcceptsDottedKey() {
    final Configuration database = root.with("database");

    assertThat(database.get("pool.size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithNamespaceCombinesNamespacesForLookups() {
    final Configuration pool = root.with("database").with("pool");

    assertThat(pool.get("size")).isEqualTo(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void nestedWithNamespaceMakesOneLookupWithTheFullKey() {
    final Configuration pool = root.with("database").with("pool");

    assertThat(pool.find("size")).contains(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void namespacedViewsCanBeUsedAsFallbackSources() {
    final Configuration database = Configuration.builder()
      .with(root.with("database"))
      .with(root.with("database.pool"))
      .build();

    assertThat(database.get("url")).isEqualTo(new Value("jdbc:postgresql://db/config"));
    assertThat(database.get("size")).isEqualTo(new Value("10"));
  }

  @Test
  void nestedWithNamespaceMatchesSingleCombinedNamespace() {
    final Configuration nested = root.with("database").with("pool");
    final Configuration combined = root.with(Key.from("database").and("pool"));

    assertThat(nested.find("size")).isEqualTo(combined.find("size"));
  }

  @Test
  void namespacedViewDoesNotSeeKeysOutsideIt() {
    final Configuration database = root.with("database");

    assertThat(database.find("timeout")).isEmpty();
    assertThat(lookups.requested).containsExactly("database.timeout");
  }

  @Test
  void namespacedGetReportsTheFullKey() {
    final Configuration database = root.with("database");

    assertThatThrownBy(() -> database.get("missing"))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: database.missing");
  }

  @Test
  void nestedNamespacedGetReportsTheFullKey() {
    final Configuration pool = root.with("database").with("pool");

    assertThatThrownBy(() -> pool.get(Key.from("max")))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: database.pool.max");
  }

  @Test
  void namespacedViewValidatesTheKey() {
    final Configuration database = root.with("database");

    assertThatThrownBy(() -> database.find(".url")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> database.get("")).isInstanceOf(IllegalArgumentException.class);
    assertThat(lookups.requested).isEmpty();
  }

  @Test
  void withDoesNotChangeTheParent() {
    root.with("database");

    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
    assertThat(lookups.requested).containsExactly("timeout");
  }

  @Test
  void withRejectsInvalidNamespace() {
    assertThatThrownBy(() -> root.with(".database")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.with("database.")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.with("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.with("data base")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullsAreRejected() {
    assertThatThrownBy(() -> Configuration.from(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("function must not be null");
    assertThatThrownBy(() -> root.find((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with("database").get((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with((Key) null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with((String) null))
      .isInstanceOf(NullPointerException.class);
  }

  // with(namespace, block)

  @Test
  void withStringBlockReceivesTheNamespacedView() {
    final List<Value> seen = new ArrayList<>();

    root.with("database", database -> seen.add(database.get("url")));

    assertThat(seen).containsExactly(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withKeyBlockReceivesTheNamespacedView() {
    final List<Value> seen = new ArrayList<>();

    root.with(Key.from("database"), database -> seen.add(database.get("url")));

    assertThat(seen).containsExactly(new Value("jdbc:postgresql://db/config"));
    assertThat(lookups.requested).containsExactly("database.url");
  }

  @Test
  void withBlockRunsExactlyOnce() {
    final List<Configuration> calls = new ArrayList<>();

    root.with("database", calls::add);

    assertThat(calls).hasSize(1);
  }

  @Test
  void withBlockGetsTheSameViewAsTheReturningForm() {
    final List<Optional<Value>> seen = new ArrayList<>();

    root.with("database.pool", pool -> seen.add(pool.find("size")));

    assertThat(seen).containsExactly(root.with("database.pool").find("size"));
  }

  @Test
  void withBlocksNest() {
    final List<Value> seen = new ArrayList<>();

    root.with("database", database ->
      database.with("pool", pool -> seen.add(pool.get("size"))));

    assertThat(seen).containsExactly(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void withBlockOnANamespacedViewAddsToItsNamespace() {
    final List<Value> seen = new ArrayList<>();

    root.with("database").with(Key.from("pool"), pool -> seen.add(pool.get("size")));

    assertThat(seen).containsExactly(new Value("10"));
    assertThat(lookups.requested).containsExactly("database.pool.size");
  }

  @Test
  void withBlockGetReportsTheFullKey() {
    assertThatThrownBy(() -> root.with("database", database -> database.get("missing")))
      .isInstanceOf(NoSuchElementException.class)
      .hasMessage("Configuration not found: database.missing");
  }

  @Test
  void withBlockDoesNotChangeTheParent() {
    root.with("database", database -> database.find("url"));

    assertThat(root.get("timeout")).isEqualTo(new Value("30s"));
    assertThat(lookups.requested).containsExactly("database.url", "timeout");
  }

  @Test
  void exceptionsFromTheBlockPropagate() {
    final IllegalStateException failure = new IllegalStateException("boom");

    assertThatThrownBy(() -> root.with("database", database -> {
      throw failure;
    })).isSameAs(failure);
  }

  @Test
  void withBlockRejectsAnInvalidNamespaceWithoutRunningTheBlock() {
    final List<Configuration> calls = new ArrayList<>();

    assertThatThrownBy(() -> root.with(".database", calls::add))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> root.with((String) null, calls::add))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with((Key) null, calls::add))
      .isInstanceOf(NullPointerException.class);
    assertThat(calls).isEmpty();
  }

  @Test
  void withBlockRejectsANullBlock() {
    assertThatThrownBy(() -> root.with("database", null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> root.with(Key.from("database"), null))
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
