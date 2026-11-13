package com.codeheadsystems.config.datastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApiTypesTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private static final SetId SETTINGS = SetId.settings(BILLING, PROD);
  private static final Key URL = Key.from("database.url");
  private static final Value URL_VALUE = new Value("jdbc:postgresql://prod/billing");

  @Nested
  class SetIds {

    @Test
    void factoriesSetTheType() {
      assertThat(SetId.settings(BILLING, PROD)).isEqualTo(new SetId(BILLING, PROD, SetType.SETTINGS));
      assertThat(SetId.metadata(BILLING, PROD)).isEqualTo(new SetId(BILLING, PROD, SetType.METADATA));
    }

    @Test
    void settingsAndMetadataAreDifferentSets() {
      assertThat(SetId.settings(BILLING, PROD)).isNotEqualTo(SetId.metadata(BILLING, PROD));
    }

    @Test
    void rejectsNulls() {
      assertThatThrownBy(() -> new SetId(null, PROD, SetType.SETTINGS))
        .isInstanceOf(NullPointerException.class).hasMessage("application must not be null");
      assertThatThrownBy(() -> new SetId(BILLING, null, SetType.SETTINGS))
        .isInstanceOf(NullPointerException.class).hasMessage("environment must not be null");
      assertThatThrownBy(() -> new SetId(BILLING, PROD, null))
        .isInstanceOf(NullPointerException.class).hasMessage("type must not be null");
    }
  }

  @Nested
  class Snapshots {

    @Test
    void copiesTheValues() {
      final Map<Key, Value> values = new HashMap<>(Map.of(URL, URL_VALUE));
      final Snapshot snapshot = new Snapshot(SETTINGS, values, 1);

      values.clear();

      assertThat(snapshot.values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
      assertThatThrownBy(() -> snapshot.values().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findLooksUpOneKey() {
      final Snapshot snapshot = new Snapshot(SETTINGS, Map.of(URL, URL_VALUE), 1);

      assertThat(snapshot.find(URL)).contains(URL_VALUE);
      assertThat(snapshot.find(Key.from("missing"))).isEmpty();
    }

    @Test
    void toConfigurationReadsTheSnapshot() {
      final Snapshot snapshot = new Snapshot(SETTINGS, Map.of(URL, URL_VALUE), 3);

      assertThat(snapshot.toConfiguration().with("database").get("url")).isEqualTo(URL_VALUE);
      assertThat(snapshot.toConfiguration().find("missing")).isEmpty();
    }

    @Test
    void versionMustBeAtLeastOne() {
      assertThatThrownBy(() -> new Snapshot(SETTINGS, Map.of(), Versions.NONE))
        .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new Snapshot(SETTINGS, Map.of(), Versions.ANY))
        .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNulls() {
      assertThatThrownBy(() -> new Snapshot(null, Map.of(), 1)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new Snapshot(SETTINGS, null, 1)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class ChangeTypes {

    @Test
    void factoriesBuildTheRecords() {
      assertThat(Change.put(URL, URL_VALUE)).isEqualTo(new Change.Put(URL, URL_VALUE));
      assertThat(Change.delete(URL)).isEqualTo(new Change.Delete(URL));
      assertThat(Change.clear()).isEqualTo(new Change.Clear());
    }

    @Test
    void rejectsNulls() {
      assertThatThrownBy(() -> Change.put(null, URL_VALUE)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> Change.put(URL, null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> Change.delete(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class ApplyingChanges {

    private final Key timeout = Key.from("timeout");

    @Test
    void appliesInOrderWithoutModifyingTheInput() {
      final Map<Key, Value> current = Map.of(URL, URL_VALUE);

      final Map<Key, Value> result = Changes.apply(current, List.of(
        Change.put(timeout, new Value("1s")),
        Change.put(timeout, new Value("30s")),
        Change.delete(URL)));

      assertThat(result).containsExactlyEntriesOf(Map.of(timeout, new Value("30s")));
      assertThat(current).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void clearRemovesEverythingBeforeIt() {
      final Map<Key, Value> result = Changes.apply(Map.of(URL, URL_VALUE), List.of(
        Change.put(timeout, new Value("1s")),
        Change.clear(),
        Change.put(Key.from("after"), new Value("kept"))));

      assertThat(result).containsExactlyEntriesOf(Map.of(Key.from("after"), new Value("kept")));
    }

    @Test
    void deletingAMissingKeyIsANoOp() {
      assertThat(Changes.apply(Map.of(URL, URL_VALUE), List.of(Change.delete(timeout))))
        .containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void resultCannotBeModified() {
      final Map<Key, Value> result = Changes.apply(Map.of(), List.of(Change.put(URL, URL_VALUE)));

      assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validateRejectsEmptyAndNull() {
      final List<Change> withNull = new java.util.ArrayList<>();
      withNull.add(null);

      assertThatThrownBy(() -> Changes.validate(List.of())).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Changes.validate(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> Changes.validate(withNull)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Sizes {

    @Test
    void countsUtf8BytesOfKeysAndValues() {
      assertThat(SetSize.bytes(Map.of())).isZero();
      assertThat(SetSize.bytes(Map.of(Key.from("ab"), new Value("cde")))).isEqualTo(5);
      // 'é' is 2 bytes, '✓' is 3, '日' is 3
      assertThat(SetSize.bytes(Map.of(Key.from("k"), new Value("é✓日")))).isEqualTo(1 + 2 + 3 + 3);
    }

    @Test
    void sumsEveryEntry() {
      assertThat(SetSize.bytes(Map.of(
        Key.from("a"), new Value("1"),
        Key.from("bb"), new Value("22")))).isEqualTo(6);
    }
  }

  @Nested
  class VersionChecks {

    @Test
    void anyMatchesEverything() {
      assertThat(Versions.matches(Versions.ANY, Versions.NONE)).isTrue();
      assertThat(Versions.matches(Versions.ANY, 7)).isTrue();
    }

    @Test
    void otherwiseTheVersionMustBeEqual() {
      assertThat(Versions.matches(Versions.NONE, Versions.NONE)).isTrue();
      assertThat(Versions.matches(Versions.NONE, 1)).isFalse();
      assertThat(Versions.matches(3, 3)).isTrue();
      assertThat(Versions.matches(3, 4)).isFalse();
      assertThat(Versions.matches(3, Versions.NONE)).isFalse();
    }

    @Test
    void versionsBelowAnyAreInvalid() {
      assertThatThrownBy(() -> Versions.validateExpected(-2)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Versions.matches(-2, 1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class Exceptions {

    @Test
    void versionConflictCarriesTheDetails() {
      final VersionConflictException e = new VersionConflictException(SETTINGS, 2, 5);

      assertThat(e).isInstanceOf(ConfigDatastoreException.class);
      assertThat(e.id()).isEqualTo(SETTINGS);
      assertThat(e.expectedVersion()).isEqualTo(2);
      assertThat(e.actualVersion()).isEqualTo(5);
      assertThat(e).hasMessageContaining("expected 2, actual 5");
    }

    @Test
    void setTooLargeCarriesTheDetails() {
      final SetTooLargeException e = new SetTooLargeException(SETTINGS, 2000, 1024);

      assertThat(e).isInstanceOf(ConfigDatastoreException.class);
      assertThat(e.id()).isEqualTo(SETTINGS);
      assertThat(e.bytes()).isEqualTo(2000);
      assertThat(e.maxBytes()).isEqualTo(1024);
      assertThat(e).hasMessageContaining("2000 bytes").hasMessageContaining("1024");
    }
  }
}
