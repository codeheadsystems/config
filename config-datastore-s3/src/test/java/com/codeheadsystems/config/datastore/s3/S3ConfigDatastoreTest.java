package com.codeheadsystems.config.datastore.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastores;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * S3-specific behaviour, beyond the shared contract: concurrency, the stored layout, awkward names,
 * the provider's options and error handling. Runs against RustFS; skipped without Docker.
 */
class S3ConfigDatastoreTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private static final SetId SETTINGS = SetId.settings(BILLING, PROD);

  private S3Client client;
  private String prefix;
  private S3ConfigDatastore datastore;

  @BeforeAll
  static void requireDocker() {
    assumeTrue(RustFs.dockerAvailable(), "Docker is not available");
  }

  @BeforeEach
  void setUp() {
    client = RustFs.client();
    prefix = RustFs.freshPrefix();
    datastore = new S3ConfigDatastore(client, RustFs.BUCKET, prefix, S3ConfigDatastore.DEFAULT_MAX_SET_BYTES);
  }

  private String object(final String key) {
    return new String(client.getObjectAsBytes(r -> r.bucket(RustFs.BUCKET).key(key)).asByteArray(),
      StandardCharsets.UTF_8);
  }

  @Nested
  class Concurrency {

    private static final int WRITERS = 16;

    private <T> List<Future<T>> runTogether(final List<Callable<T>> tasks) throws InterruptedException {
      final ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
      try {
        return pool.invokeAll(tasks);
      } finally {
        pool.shutdown();
      }
    }

    @Test
    void writersIgnoringVersionsNeverLoseAnUpdate() throws Exception {
      datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final List<Callable<Long>> tasks = new ArrayList<>();
      for (int i = 0; i < WRITERS; i++) {
        final int n = i;
        tasks.add(() -> datastore.put(SETTINGS, Versions.ANY, Key.from("writer." + n), new Value("v" + n)));
      }

      final List<Long> versions = new ArrayList<>();
      for (final Future<Long> result : runTogether(tasks)) {
        versions.add(result.get());
      }

      final Map<Key, Value> values = datastore.load(SETTINGS).orElseThrow().values();
      assertThat(values).hasSize(WRITERS);
      assertThat(versions).doesNotHaveDuplicates();
      assertThat(datastore.version(SETTINGS)).isEqualTo(1 + WRITERS);
    }

    @Test
    void writersExpectingTheSameVersionHaveExactlyOneWinner() throws Exception {
      final long version = datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < WRITERS; i++) {
        final int n = i;
        tasks.add(() -> {
          try {
            datastore.put(SETTINGS, version, Key.from("winner"), new Value("w" + n));
            return true;
          } catch (final VersionConflictException e) {
            return false;
          }
        });
      }

      int winners = 0;
      for (final Future<Boolean> result : runTogether(tasks)) {
        winners += result.get() ? 1 : 0;
      }

      assertThat(winners).isEqualTo(1);
      assertThat(datastore.version(SETTINGS)).isEqualTo(version + 1);
    }

    @Test
    void concurrentCreatorsHaveExactlyOneWinner() throws Exception {
      final List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < WRITERS; i++) {
        final int n = i;
        tasks.add(() -> {
          try {
            datastore.put(SETTINGS, Versions.NONE, Key.from("creator"), new Value("c" + n));
            return true;
          } catch (final VersionConflictException e) {
            return false;
          }
        });
      }

      int winners = 0;
      for (final Future<Boolean> result : runTogether(tasks)) {
        winners += result.get() ? 1 : 0;
      }

      assertThat(winners).isEqualTo(1);
    }

    @Test
    void deletersExpectingTheSameVersionHaveExactlyOneWinner() throws Exception {
      final long version = datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("a"), new Value("b")));
      final List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < WRITERS; i++) {
        tasks.add(() -> {
          try {
            return datastore.delete(SETTINGS, version);
          } catch (final VersionConflictException e) {
            return false;
          }
        });
      }

      int winners = 0;
      for (final Future<Boolean> result : runTogether(tasks)) {
        winners += result.get() ? 1 : 0;
      }

      assertThat(winners).isEqualTo(1);
      assertThat(datastore.load(SETTINGS)).isEmpty();
      assertThat(datastore.replace(SETTINGS, Versions.NONE, Map.of())).isGreaterThan(version);
    }
  }

  @Nested
  class StoredLayout {

    @Test
    void aSetIsOneJsonObjectWithItsVersionInMetadata() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("timeout"), new Value("30s")));

      final String key = prefix + "sets/_billing/_prod/settings";
      assertThat(object(key)).isEqualTo("{\"version\":1,\"values\":{\"timeout\":\"30s\"}}");
      final HeadObjectResponse head = client.headObject(r -> r.bucket(RustFs.BUCKET).key(key));
      assertThat(head.metadata()).containsEntry(S3ConfigDatastore.VERSION_METADATA, "1");
      assertThat(head.contentType()).isEqualTo("application/json");
    }

    @Test
    void deletingLeavesATombstoneWithTheLastVersion() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("timeout"), new Value("30s")));
      final long last = datastore.put(SETTINGS, Versions.ANY, Key.from("retries"), new Value("3"));

      datastore.delete(SETTINGS, last);

      assertThat(object(prefix + "tombstones/_billing/_prod/settings"))
        .isEqualTo("{\"version\":" + last + ",\"values\":{}}");
    }

    @Test
    void foreignObjectsUnderThePrefixAreIgnoredByListings() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of());
      for (final String key : List.of("sets/notours/x", "sets/_billing/notours/x", "sets/_billing/_prod/other",
        "elsewhere/_billing/_prod/settings")) {
        client.putObject(r -> r.bucket(RustFs.BUCKET).key(prefix + key), RequestBody.fromString("junk"));
      }

      assertThat(datastore.applications()).containsExactly(BILLING);
      assertThat(datastore.environments(BILLING)).containsExactly(PROD);
      assertThat(datastore.types(BILLING, PROD)).containsExactly(SetType.SETTINGS);
    }

    @Test
    void anUnreadableObjectIsADatastoreError() {
      client.putObject(r -> r.bucket(RustFs.BUCKET).key(prefix + "sets/_billing/_prod/settings"),
        RequestBody.fromString("not json"));

      assertThatThrownBy(() -> datastore.load(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("sets/_billing/_prod/settings");
    }

    @Test
    void datastoresWithDifferentPrefixesAreSeparate() {
      final S3ConfigDatastore other = new S3ConfigDatastore(client, RustFs.BUCKET, RustFs.freshPrefix(), 1024);
      datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("a"), new Value("b")));

      assertThat(other.load(SETTINGS)).isEmpty();
      assertThat(other.applications()).isEmpty();
    }
  }

  @Nested
  class Names {

    @Test
    void awkwardApplicationAndEnvironmentNamesRoundTrip() {
      final Map<SetId, Value> sets = new HashMap<>();
      for (final String name : List.of("a/b", "a b", "a+b", "100%", "héllo ✓", "", "_x", "billing/_prod")) {
        sets.put(SetId.settings(Application.from(name), Environment.from(name + "-env")), new Value(name));
      }
      sets.forEach((id, value) -> datastore.put(id, Versions.NONE, Key.from("name"), value));

      assertThat(datastore.applications()).hasSize(sets.size());
      sets.forEach((id, value) -> {
        assertThat(datastore.find(id, Key.from("name"))).contains(value);
        assertThat(datastore.environments(id.application())).containsExactly(id.environment());
        assertThat(datastore.types(id.application(), id.environment())).containsExactly(SetType.SETTINGS);
      });
    }

    @Test
    void applicationsAreListedPastOnePageOfResults() {
      for (int i = 0; i < 1005; i++) {
        datastore.put(SetId.metadata(Application.from("app-" + i), PROD), Versions.NONE, Key.from("k"), new Value("v"));
      }

      assertThat(datastore.applications()).hasSize(1005);
    }
  }

  @Nested
  class Provider {

    private Configuration options(final Map<String, String> overrides) {
      final Map<String, String> values = new HashMap<>(Map.of(
        "bucket", RustFs.BUCKET,
        "prefix", prefix,
        "endpoint", RustFs.endpoint().toString(),
        "path-style", "true",
        "access-key", RustFs.ACCESS_KEY,
        "secret-key", RustFs.SECRET_KEY));
      values.putAll(overrides);
      values.values().removeIf(String::isEmpty);
      return Configuration.builder().with(values).build();
    }

    @Test
    void theS3BackendIsAvailable() {
      assertThat(ConfigDatastores.available()).contains("s3", "memory");
    }

    @Test
    void openCreatesAWorkingDatastoreOverTheSameObjects() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("timeout"), new Value("30s")));

      try (ConfigDatastore opened = ConfigDatastores.open("s3", options(Map.of("max-set-bytes", "2048")))) {
        assertThat(opened).isInstanceOf(S3ConfigDatastore.class);
        assertThat(opened.maxSetBytes()).isEqualTo(2048);
        assertThat(opened.find(SETTINGS, Key.from("timeout"))).contains(new Value("30s"));
      }
    }

    @Test
    void defaultsApply() {
      try (ConfigDatastore opened = ConfigDatastores.open("s3", options(Map.of()))) {
        assertThat(opened.maxSetBytes()).isEqualTo(S3ConfigDatastore.DEFAULT_MAX_SET_BYTES);
      }
    }

    @Test
    void namespacedBootstrapOptionsWork() {
      final Map<String, String> bootstrap = new HashMap<>();
      bootstrap.put("datastore.s3.bucket", RustFs.BUCKET);
      bootstrap.put("datastore.s3.prefix", prefix);
      bootstrap.put("datastore.s3.endpoint", RustFs.endpoint().toString());
      bootstrap.put("datastore.s3.path-style", "true");
      bootstrap.put("datastore.s3.access-key", RustFs.ACCESS_KEY);
      bootstrap.put("datastore.s3.secret-key", RustFs.SECRET_KEY);

      try (ConfigDatastore opened = ConfigDatastores.open("s3",
        Configuration.builder().with(bootstrap).build().with("datastore.s3"))) {
        assertThat(opened.load(SETTINGS)).isEmpty();
      }
    }

    @Test
    void bucketIsRequired() {
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("bucket", ""))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("bucket");
    }

    @Test
    void accessKeyWithoutSecretKeyIsRejected() {
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("secret-key", ""))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("access-key");
    }

    @Test
    void aMissingBucketFailsWhenOpened() {
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("bucket", "no-such-bucket"))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("no-such-bucket");
    }

    @Test
    void wrongCredentialsFailWhenOpened() {
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("secret-key", "wrong-secret"))))
        .isInstanceOf(ConfigDatastoreException.class);
    }
  }

  @Nested
  class MissingBucket {

    private final S3ConfigDatastore unchecked =
      new S3ConfigDatastore(RustFs.client(), "no-such-bucket", "config/", 1024);

    @Test
    void loadFailsInsteadOfLookingEmpty() {
      assertThatThrownBy(() -> unchecked.load(SETTINGS)).isInstanceOf(ConfigDatastoreException.class);
    }

    @Test
    void writesFailInsteadOfLookingLikeAConflict() {
      assertThatThrownBy(() -> unchecked.put(SETTINGS, Versions.ANY, Key.from("a"), new Value("b")))
        .isInstanceOf(ConfigDatastoreException.class)
        .isNotInstanceOf(VersionConflictException.class);
    }

    @Test
    void listingsFail() {
      assertThatThrownBy(unchecked::applications).isInstanceOf(ConfigDatastoreException.class);
    }
  }

  @Nested
  class Lifecycle {

    @Test
    void closeLeavesACallerSuppliedClientOpen() {
      datastore.close();

      assertThat(datastore.load(SETTINGS)).isEmpty(); // the shared client still works
    }

    @Test
    void constructorValidatesItsArguments() {
      assertThatThrownBy(() -> new S3ConfigDatastore(null, "b", "p", 1)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new S3ConfigDatastore(client, null, "p", 1)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new S3ConfigDatastore(client, "b", null, 1)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new S3ConfigDatastore(client, "b", "p", 0)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
