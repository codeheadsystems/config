package com.codeheadsystems.config.datastore.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetSize;
import com.codeheadsystems.config.datastore.SetTooLargeException;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link ConfigDatastore} must honour. A backend's test class extends this and
 * supplies its datastore; the inherited tests do the rest.
 */
public abstract class ConfigDatastoreContract {

  /**
   * The largest size limit the size tests will exercise; above this they are skipped, since they
   * build a set of exactly the limit.
   */
  protected static final long MAX_TESTED_SET_BYTES = 8L * 1024L * 1024L;

  private static final Application BILLING = Application.from("billing");
  private static final Application SHIPPING = Application.from("shipping");
  private static final Environment PROD = Environment.from("prod");
  private static final Environment DEV = Environment.from("dev");
  private static final SetId SETTINGS = SetId.settings(BILLING, PROD);
  private static final SetId METADATA = SetId.metadata(BILLING, PROD);
  private static final Key URL = Key.from("database.url");
  private static final Key TIMEOUT = Key.from("timeout");
  private static final Value URL_VALUE = new Value("jdbc:postgresql://prod/billing");
  private static final Value TIMEOUT_VALUE = new Value("30s");

  private ConfigDatastore datastore;

  /**
   * For subclasses.
   */
  protected ConfigDatastoreContract() {
  }

  /**
   * Supplies the datastore under test. Called before each test.
   *
   * @return a datastore backed by a fresh, empty store.
   */
  protected abstract ConfigDatastore datastore();

  @BeforeEach
  void openDatastore() {
    datastore = datastore();
  }

  @AfterEach
  void closeDatastore() {
    if (datastore != null) {
      datastore.close();
    }
  }

  private long create(final SetId id, final Map<Key, Value> values) {
    return datastore.replace(id, Versions.NONE, values);
  }

  private Snapshot loaded(final SetId id) {
    return datastore.load(id).orElseThrow(() -> new AssertionError("expected " + id + " to exist"));
  }

  /** Reading sets that do not exist. */
  @Nested
  class Missing {

    @Test
    void loadIsEmpty() {
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void versionIsNone() {
      assertThat(datastore.version(SETTINGS)).isEqualTo(Versions.NONE);
    }

    @Test
    void findIsEmpty() {
      assertThat(datastore.find(SETTINGS, URL)).isEmpty();
    }

    @Test
    void settingsIsEmpty() {
      assertThat(datastore.settings(BILLING, PROD)).isEmpty();
    }

    @Test
    void listingsAreEmpty() {
      assertThat(datastore.applications()).isEmpty();
      assertThat(datastore.environments(BILLING)).isEmpty();
      assertThat(datastore.types(BILLING, PROD)).isEmpty();
    }
  }

  /** Creating a set and reading it back. */
  @Nested
  class CreateAndRead {

    @Test
    void applyWithNoneCreatesTheSet() {
      final long version = datastore.apply(SETTINGS, Versions.NONE, List.of(Change.put(URL, URL_VALUE)));

      assertThat(version).isGreaterThanOrEqualTo(1);
      final Snapshot snapshot = loaded(SETTINGS);
      assertThat(snapshot.id()).isEqualTo(SETTINGS);
      assertThat(snapshot.values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
      assertThat(snapshot.version()).isEqualTo(version);
    }

    @Test
    void applyWithAnyCreatesTheSet() {
      datastore.apply(SETTINGS, Versions.ANY, List.of(Change.put(URL, URL_VALUE)));

      assertThat(datastore.find(SETTINGS, URL)).contains(URL_VALUE);
    }

    @Test
    void returnedVersionMatchesVersionAndLoad() {
      final long version = create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThat(datastore.version(SETTINGS)).isEqualTo(version);
      assertThat(loaded(SETTINGS).version()).isEqualTo(version);
    }

    @Test
    void findReturnsOneValue() {
      create(SETTINGS, Map.of(URL, URL_VALUE, TIMEOUT, TIMEOUT_VALUE));

      assertThat(datastore.find(SETTINGS, TIMEOUT)).contains(TIMEOUT_VALUE);
      assertThat(datastore.find(SETTINGS, Key.from("missing"))).isEmpty();
    }

    @Test
    void settingsReadsTheSettingsSet() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(METADATA, Map.of(Key.from("owner"), new Value("team-billing")));

      final Snapshot settings = datastore.settings(BILLING, PROD).orElseThrow();
      assertThat(settings.id().type()).isEqualTo(SetType.SETTINGS);
      assertThat(settings.values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void valuesRoundTripExactly() {
      final Map<Key, Value> values = Map.of(
        Key.from("empty"), new Value(""),
        Key.from("unicode"), new Value("héllo wörld ✓ 日本"),
        Key.from("whitespace"), new Value("  spaced\ttabbed\nnewline  "),
        Key.from("symbols"), new Value("a=b;c:d,e{f}[g]\"h\"'i'\\j"));
      create(SETTINGS, values);

      assertThat(loaded(SETTINGS).values()).containsExactlyInAnyOrderEntriesOf(values);
    }

    @Test
    void manyKeysRoundTrip() {
      final Map<Key, Value> values = new HashMap<>();
      for (int i = 0; i < 500; i++) {
        values.put(Key.from("key." + i), new Value("value-" + i));
      }
      assumeTrue(SetSize.bytes(values) <= datastore.maxSetBytes(), "limit too small for 500 keys");
      create(SETTINGS, values);

      assertThat(loaded(SETTINGS).values()).containsExactlyInAnyOrderEntriesOf(values);
    }

    @Test
    void snapshotIsAnIndependentCopy() {
      final Map<Key, Value> values = new HashMap<>(Map.of(URL, URL_VALUE));
      create(SETTINGS, values);
      final Snapshot before = loaded(SETTINGS);

      values.put(TIMEOUT, TIMEOUT_VALUE);
      datastore.put(SETTINGS, Versions.ANY, TIMEOUT, TIMEOUT_VALUE);

      assertThat(before.values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void snapshotWorksAsAConfiguration() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThat(loaded(SETTINGS).toConfiguration().with("database").get("url")).isEqualTo(URL_VALUE);
    }
  }

  /** Changes within one write. */
  @Nested
  class ApplyingChanges {

    @Test
    void changesAreAppliedInOrder() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      datastore.apply(SETTINGS, Versions.ANY, List.of(
        Change.put(TIMEOUT, new Value("10s")),
        Change.put(TIMEOUT, TIMEOUT_VALUE),
        Change.delete(URL),
        Change.put(Key.from("added.then.removed"), new Value("x")),
        Change.delete(Key.from("added.then.removed"))));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(TIMEOUT, TIMEOUT_VALUE));
    }

    @Test
    void clearThenPutReplacesTheSet() {
      create(SETTINGS, Map.of(URL, URL_VALUE, Key.from("old"), new Value("gone")));

      datastore.apply(SETTINGS, Versions.ANY, List.of(Change.clear(), Change.put(TIMEOUT, TIMEOUT_VALUE)));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(TIMEOUT, TIMEOUT_VALUE));
    }

    @Test
    void putAddsOrOverwritesOneKey() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      datastore.put(SETTINGS, Versions.ANY, TIMEOUT, TIMEOUT_VALUE);
      datastore.put(SETTINGS, Versions.ANY, URL, new Value("jdbc:postgresql://new/billing"));

      assertThat(loaded(SETTINGS).values()).containsExactlyInAnyOrderEntriesOf(Map.of(
        URL, new Value("jdbc:postgresql://new/billing"),
        TIMEOUT, TIMEOUT_VALUE));
    }

    @Test
    void removeDeletesOneKey() {
      create(SETTINGS, Map.of(URL, URL_VALUE, TIMEOUT, TIMEOUT_VALUE));

      datastore.remove(SETTINGS, Versions.ANY, URL);

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(TIMEOUT, TIMEOUT_VALUE));
    }

    @Test
    void removingAMissingKeyIsNotAnError() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      datastore.remove(SETTINGS, Versions.ANY, Key.from("missing"));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void replaceSetsExactlyTheGivenValues() {
      create(SETTINGS, Map.of(URL, URL_VALUE, Key.from("old"), new Value("gone")));

      datastore.replace(SETTINGS, Versions.ANY, Map.of(TIMEOUT, TIMEOUT_VALUE));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(TIMEOUT, TIMEOUT_VALUE));
    }

    @Test
    void emptyChangesAreRejected() {
      assertThatThrownBy(() -> datastore.apply(SETTINGS, Versions.ANY, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void nullChangesAreRejected() {
      final List<Change> withNull = new ArrayList<>();
      withNull.add(Change.put(URL, URL_VALUE));
      withNull.add(null);

      assertThatThrownBy(() -> datastore.apply(SETTINGS, Versions.ANY, null))
        .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> datastore.apply(SETTINGS, Versions.ANY, withNull))
        .isInstanceOf(NullPointerException.class);
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void invalidExpectedVersionIsRejected() {
      assertThatThrownBy(() -> datastore.put(SETTINGS, -2, URL, URL_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }
  }

  /** An empty set is still a set. */
  @Nested
  class EmptySets {

    @Test
    void clearingLeavesAnEmptySetThatExists() {
      final long created = create(SETTINGS, Map.of(URL, URL_VALUE));

      final long cleared = datastore.apply(SETTINGS, created, List.of(Change.clear()));

      final Snapshot snapshot = loaded(SETTINGS);
      assertThat(snapshot.values()).isEmpty();
      assertThat(snapshot.version()).isEqualTo(cleared);
      assertThat(datastore.types(BILLING, PROD)).containsExactly(SetType.SETTINGS);
    }

    @Test
    void creatingWithNoValuesMakesAnEmptySet() {
      create(SETTINGS, Map.of());

      assertThat(loaded(SETTINGS).values()).isEmpty();
      assertThat(datastore.applications()).containsExactly(BILLING);
    }
  }

  /** Versions and the version check. */
  @Nested
  class VersionChecks {

    @Test
    void everyWriteIncreasesTheVersion() {
      final long first = create(SETTINGS, Map.of(URL, URL_VALUE));
      final long second = datastore.put(SETTINGS, first, TIMEOUT, TIMEOUT_VALUE);
      final long third = datastore.put(SETTINGS, second, TIMEOUT, TIMEOUT_VALUE); // same value
      final long fourth = datastore.remove(SETTINGS, third, Key.from("missing")); // no-op

      assertThat(second).isGreaterThan(first);
      assertThat(third).isGreaterThan(second);
      assertThat(fourth).isGreaterThan(third);
      assertThat(datastore.version(SETTINGS)).isEqualTo(fourth);
    }

    @Test
    void writeWithTheCurrentVersionSucceeds() {
      final long version = create(SETTINGS, Map.of(URL, URL_VALUE));

      datastore.put(SETTINGS, version, TIMEOUT, TIMEOUT_VALUE);

      assertThat(datastore.find(SETTINGS, TIMEOUT)).contains(TIMEOUT_VALUE);
    }

    @Test
    void staleVersionIsRejectedAndTheSetIsUnchanged() {
      final long stale = create(SETTINGS, Map.of(URL, URL_VALUE));
      final long current = datastore.put(SETTINGS, stale, TIMEOUT, TIMEOUT_VALUE);

      assertThatThrownBy(() -> datastore.put(SETTINGS, stale, URL, new Value("lost update")))
        .isInstanceOfSatisfying(VersionConflictException.class, e -> {
          assertThat(e.id()).isEqualTo(SETTINGS);
          assertThat(e.expectedVersion()).isEqualTo(stale);
          assertThat(e.actualVersion()).isEqualTo(current);
        });
      assertThat(loaded(SETTINGS).values()).containsExactlyInAnyOrderEntriesOf(Map.of(
        URL, URL_VALUE, TIMEOUT, TIMEOUT_VALUE));
      assertThat(datastore.version(SETTINGS)).isEqualTo(current);
    }

    @Test
    void noneIsRejectedWhenTheSetExists() {
      final long version = create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThatThrownBy(() -> datastore.put(SETTINGS, Versions.NONE, TIMEOUT, TIMEOUT_VALUE))
        .isInstanceOfSatisfying(VersionConflictException.class, e -> {
          assertThat(e.expectedVersion()).isEqualTo(Versions.NONE);
          assertThat(e.actualVersion()).isEqualTo(version);
        });
      assertThat(datastore.find(SETTINGS, TIMEOUT)).isEmpty();
    }

    @Test
    void aRealVersionIsRejectedWhenTheSetDoesNotExist() {
      assertThatThrownBy(() -> datastore.put(SETTINGS, 5, URL, URL_VALUE))
        .isInstanceOfSatisfying(VersionConflictException.class, e ->
          assertThat(e.actualVersion()).isEqualTo(Versions.NONE));
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void anyIgnoresTheCurrentVersion() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      datastore.put(SETTINGS, Versions.ANY, TIMEOUT, new Value("1s"));

      datastore.put(SETTINGS, Versions.ANY, TIMEOUT, TIMEOUT_VALUE);

      assertThat(datastore.find(SETTINGS, TIMEOUT)).contains(TIMEOUT_VALUE);
    }
  }

  /** Deleting whole sets. */
  @Nested
  class Deleting {

    @Test
    void deleteRemovesTheSet() {
      final long version = create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThat(datastore.delete(SETTINGS, version)).isTrue();

      assertThat(datastore.load(SETTINGS)).isEmpty();
      assertThat(datastore.version(SETTINGS)).isEqualTo(Versions.NONE);
      assertThat(datastore.find(SETTINGS, URL)).isEmpty();
    }

    @Test
    void deleteWithAnySucceeds() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThat(datastore.delete(SETTINGS, Versions.ANY)).isTrue();
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void deletingAMissingSetReturnsFalse() {
      assertThat(datastore.delete(SETTINGS, Versions.ANY)).isFalse();
      assertThat(datastore.delete(SETTINGS, Versions.NONE)).isFalse();
    }

    @Test
    void deleteWithAStaleVersionIsRejectedAndTheSetIsKept() {
      final long stale = create(SETTINGS, Map.of(URL, URL_VALUE));
      datastore.put(SETTINGS, stale, TIMEOUT, TIMEOUT_VALUE);

      assertThatThrownBy(() -> datastore.delete(SETTINGS, stale))
        .isInstanceOf(VersionConflictException.class);
      assertThat(datastore.find(SETTINGS, URL)).contains(URL_VALUE);
    }

    @Test
    void deleteWithARealVersionOnAMissingSetIsRejected() {
      assertThatThrownBy(() -> datastore.delete(SETTINGS, 3))
        .isInstanceOfSatisfying(VersionConflictException.class, e ->
          assertThat(e.actualVersion()).isEqualTo(Versions.NONE));
    }

    @Test
    void aRecreatedSetContinuesPastItsOldVersion() {
      final long first = create(SETTINGS, Map.of(URL, URL_VALUE));
      final long last = datastore.put(SETTINGS, first, TIMEOUT, TIMEOUT_VALUE);
      datastore.delete(SETTINGS, last);

      final long recreated = create(SETTINGS, Map.of(URL, new Value("new")));

      assertThat(recreated).isGreaterThan(last);
    }

    @Test
    void aRecreatedSetHasOnlyItsNewValues() {
      create(SETTINGS, Map.of(URL, URL_VALUE, TIMEOUT, TIMEOUT_VALUE));
      datastore.delete(SETTINGS, Versions.ANY);

      datastore.put(SETTINGS, Versions.NONE, URL, new Value("new"));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, new Value("new")));
    }

    @Test
    void deletedSetsLeaveTheListings() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(METADATA, Map.of(Key.from("owner"), new Value("team")));

      datastore.delete(SETTINGS, Versions.ANY);
      assertThat(datastore.types(BILLING, PROD)).containsExactly(SetType.METADATA);
      assertThat(datastore.applications()).containsExactly(BILLING);

      datastore.delete(METADATA, Versions.ANY);
      assertThat(datastore.types(BILLING, PROD)).isEmpty();
      assertThat(datastore.environments(BILLING)).isEmpty();
      assertThat(datastore.applications()).isEmpty();
    }
  }

  /** Sets never affect each other. */
  @Nested
  class Isolation {

    @Test
    void settingsAndMetadataAreSeparate() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(METADATA, Map.of(URL, new Value("not the settings")));

      datastore.put(METADATA, Versions.ANY, TIMEOUT, TIMEOUT_VALUE);

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
      assertThat(datastore.find(METADATA, URL)).contains(new Value("not the settings"));
    }

    @Test
    void environmentsAreSeparate() {
      final SetId dev = SetId.settings(BILLING, DEV);
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(dev, Map.of(URL, new Value("jdbc:postgresql://dev/billing")));

      datastore.delete(dev, Versions.ANY);

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void applicationsAreSeparate() {
      final SetId shipping = SetId.settings(SHIPPING, PROD);
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(shipping, Map.of(URL, new Value("jdbc:postgresql://prod/shipping")));

      datastore.replace(shipping, Versions.ANY, Map.of());

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }

    @Test
    void versionsAreTrackedPerSet() {
      final long settingsVersion = create(SETTINGS, Map.of(URL, URL_VALUE));
      create(METADATA, Map.of(URL, URL_VALUE));
      datastore.put(METADATA, Versions.ANY, TIMEOUT, TIMEOUT_VALUE);

      assertThat(datastore.version(SETTINGS)).isEqualTo(settingsVersion);
      datastore.put(SETTINGS, settingsVersion, TIMEOUT, TIMEOUT_VALUE); // no conflict
    }

    @Test
    void namesThatShareAPrefixAreSeparate() {
      final SetId bill = SetId.settings(Application.from("bill"), PROD);
      final SetId prodEu = SetId.settings(BILLING, Environment.from("prod-eu"));
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(bill, Map.of(URL, new Value("bill")));
      create(prodEu, Map.of(URL, new Value("prod-eu")));

      assertThat(datastore.find(SETTINGS, URL)).contains(URL_VALUE);
      assertThat(datastore.environments(BILLING)).containsExactlyInAnyOrder(PROD, Environment.from("prod-eu"));
    }
  }

  /** Listing applications, environments and set types. */
  @Nested
  class Listings {

    @Test
    void listingsReflectTheSetsThatExist() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      create(METADATA, Map.of(Key.from("owner"), new Value("team")));
      create(SetId.settings(BILLING, DEV), Map.of(URL, URL_VALUE));
      create(SetId.metadata(SHIPPING, PROD), Map.of(Key.from("owner"), new Value("team")));

      assertThat(datastore.applications()).containsExactlyInAnyOrder(BILLING, SHIPPING);
      assertThat(datastore.environments(BILLING)).containsExactlyInAnyOrder(PROD, DEV);
      assertThat(datastore.environments(SHIPPING)).containsExactly(PROD);
      assertThat(datastore.types(BILLING, PROD)).containsExactlyInAnyOrder(SetType.SETTINGS, SetType.METADATA);
      assertThat(datastore.types(BILLING, DEV)).containsExactly(SetType.SETTINGS);
      assertThat(datastore.types(SHIPPING, PROD)).containsExactly(SetType.METADATA);
    }

    @Test
    void unknownApplicationOrEnvironmentListsNothing() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThat(datastore.environments(SHIPPING)).isEmpty();
      assertThat(datastore.types(BILLING, DEV)).isEmpty();
    }
  }

  /** The backend's size limit. */
  @Nested
  class SizeLimit {

    private long limit() {
      final long limit = datastore.maxSetBytes();
      assertThat(limit).as("maxSetBytes").isPositive();
      assumeTrue(limit <= MAX_TESTED_SET_BYTES, "limit too large to exercise: " + limit);
      assumeTrue(limit >= 2, "limit too small to exercise: " + limit);
      return limit;
    }

    /** One key, "k", whose value brings the set to exactly {@code bytes}. */
    private Map<Key, Value> setOfSize(final long bytes) {
      final Map<Key, Value> values = Map.of(Key.from("k"), new Value("x".repeat((int) (bytes - 1))));
      assertThat(SetSize.bytes(values)).isEqualTo(bytes);
      return values;
    }

    @Test
    void maxSetBytesIsPositive() {
      assertThat(datastore.maxSetBytes()).isPositive();
    }

    @Test
    void aSetOfExactlyTheLimitIsAccepted() {
      final long limit = limit();

      create(SETTINGS, setOfSize(limit));

      assertThat(SetSize.bytes(loaded(SETTINGS).values())).isEqualTo(limit);
    }

    @Test
    void aSetOverTheLimitIsRejected() {
      final long limit = limit();

      assertThatThrownBy(() -> create(SETTINGS, setOfSize(limit + 1)))
        .isInstanceOfSatisfying(SetTooLargeException.class, e -> {
          assertThat(e.id()).isEqualTo(SETTINGS);
          assertThat(e.bytes()).isEqualTo(limit + 1);
          assertThat(e.maxBytes()).isEqualTo(limit);
        });
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void aWriteThatWouldGrowPastTheLimitLeavesTheSetUnchanged() {
      final long limit = limit();
      final long version = create(SETTINGS, setOfSize(limit));

      assertThatThrownBy(() -> datastore.put(SETTINGS, version, Key.from("a"), new Value("b")))
        .isInstanceOf(SetTooLargeException.class);
      assertThat(datastore.version(SETTINGS)).isEqualTo(version);
      assertThat(datastore.find(SETTINGS, Key.from("a"))).isEmpty();
    }

    @Test
    void theLimitAppliesToTheResultNotToTheChanges() {
      final long limit = limit();
      create(SETTINGS, setOfSize(limit));

      // Removing the big key and adding a small one stays within the limit.
      datastore.apply(SETTINGS, Versions.ANY, List.of(
        Change.delete(Key.from("k")),
        Change.put(URL, URL_VALUE)));

      assertThat(loaded(SETTINGS).values()).containsExactlyEntriesOf(Map.of(URL, URL_VALUE));
    }
  }

  /** Atomicity: a failed write changes nothing. */
  @Nested
  class Atomicity {

    @Test
    void aConflictingMultiChangeWriteChangesNothing() {
      final long stale = create(SETTINGS, Map.of(URL, URL_VALUE));
      datastore.put(SETTINGS, stale, TIMEOUT, TIMEOUT_VALUE);
      final Snapshot before = loaded(SETTINGS);

      assertThatThrownBy(() -> datastore.apply(SETTINGS, stale, List.of(
        Change.clear(),
        Change.put(Key.from("new"), new Value("value")))))
        .isInstanceOf(VersionConflictException.class);

      assertThat(loaded(SETTINGS)).isEqualTo(before);
    }

    @Test
    void anOversizedMultiChangeWriteChangesNothing() {
      final long limit = datastore.maxSetBytes();
      assumeTrue(limit <= MAX_TESTED_SET_BYTES, "limit too large to exercise: " + limit);
      create(SETTINGS, Map.of(URL, URL_VALUE));
      final Snapshot before = loaded(SETTINGS);

      assertThatThrownBy(() -> datastore.apply(SETTINGS, Versions.ANY, List.of(
        Change.delete(URL),
        Change.put(TIMEOUT, TIMEOUT_VALUE),
        Change.put(Key.from("big"), new Value("x".repeat((int) limit))))))
        .isInstanceOf(SetTooLargeException.class);

      assertThat(loaded(SETTINGS)).isEqualTo(before);
    }

    @Test
    void aRejectedCreateLeavesNoSet() {
      final long limit = datastore.maxSetBytes();
      assumeTrue(limit <= MAX_TESTED_SET_BYTES, "limit too large to exercise: " + limit);

      assertThatThrownBy(() -> datastore.put(SETTINGS, Versions.NONE, Key.from("big"),
        new Value("x".repeat((int) limit))))
        .isInstanceOf(SetTooLargeException.class);

      assertThat(datastore.load(SETTINGS)).isEmpty();
      assertThat(datastore.applications()).isEmpty();
      assertThat(datastore.version(SETTINGS)).isEqualTo(Versions.NONE);
    }
  }

  /** Results are values, not live views. */
  @Nested
  class Results {

    @Test
    void loadedValuesCannotBeModified() {
      create(SETTINGS, Map.of(URL, URL_VALUE));
      final Map<Key, Value> values = loaded(SETTINGS).values();

      assertThatThrownBy(() -> values.put(TIMEOUT, TIMEOUT_VALUE))
        .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listingsCannotBeModified() {
      create(SETTINGS, Map.of(URL, URL_VALUE));

      assertThatThrownBy(() -> datastore.applications().add(SHIPPING))
        .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> datastore.environments(BILLING).add(DEV))
        .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> datastore.types(BILLING, PROD).add(SetType.METADATA))
        .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findMatchesLoad() {
      create(SETTINGS, Map.of(URL, URL_VALUE, TIMEOUT, TIMEOUT_VALUE));
      final Snapshot snapshot = loaded(SETTINGS);

      for (final Key key : List.of(URL, TIMEOUT, Key.from("missing"))) {
        assertThat(datastore.find(SETTINGS, key)).isEqualTo(Optional.ofNullable(snapshot.values().get(key)));
      }
    }
  }
}
