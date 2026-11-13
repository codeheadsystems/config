package com.codeheadsystems.config.datastore.s3;

import static com.codeheadsystems.config.datastore.s3.InterceptingS3Client.error;
import static com.codeheadsystems.config.datastore.s3.InterceptingS3Client.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastores;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-specific behaviour beyond the shared contract: the stored layout, races forced at an exact
 * point, a missing bucket, validation, the provider's options and the client's lifecycle. Runs
 * against RustFS; skipped without Docker, except on CI.
 */
class S3ConfigDatastoreTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private static final SetId SETTINGS = SetId.settings(BILLING, PROD);
  private static final Key TIMEOUT = Key.from("timeout");

  private S3Client client;
  private String prefix;
  private String settingsKey;
  private S3ConfigDatastore datastore;

  @BeforeAll
  static void requireDocker() {
    RustFs.requireDocker();
  }

  @BeforeEach
  void setUp() {
    client = RustFs.client();
    prefix = RustFs.freshPrefix();
    settingsKey = prefix + "sets/billing/prod/settings";
    datastore = new S3ConfigDatastore(client, RustFs.BUCKET, prefix, S3ConfigDatastore.DEFAULT_MAX_SET_BYTES);
  }

  /** A datastore over the same objects, whose S3 calls go through an interceptor. */
  private S3ConfigDatastore intercepted(final InterceptingS3Client.Interceptor interceptor) {
    return new S3ConfigDatastore(InterceptingS3Client.wrap(client, interceptor), RustFs.BUCKET, prefix,
      S3ConfigDatastore.DEFAULT_MAX_SET_BYTES);
  }

  private String body(final String key) {
    return new String(client.getObjectAsBytes(r -> r.bucket(RustFs.BUCKET).key(key)).asByteArray(),
      StandardCharsets.UTF_8);
  }

  private HeadObjectResponse head(final String key) {
    return client.headObject(r -> r.bucket(RustFs.BUCKET).key(key));
  }

  @Nested
  class StoredLayout {

    @Test
    void aSetIsOneJsonObjectWithItsVersionAndStateInMetadata() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of(TIMEOUT, new Value("30s")));

      assertThat(body(settingsKey)).isEqualTo("{\"format\":1,\"version\":1,\"values\":{\"timeout\":\"30s\"}}");
      final HeadObjectResponse head = head(settingsKey);
      assertThat(head.metadata())
        .containsEntry(S3ConfigDatastore.VERSION_METADATA, "1")
        .containsEntry(S3ConfigDatastore.STATE_METADATA, "live");
      assertThat(head.contentType()).isEqualTo("application/json");
    }

    @Test
    void deletingOverwritesTheSetWithAMarkerKeepingTheVersion() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of(TIMEOUT, new Value("30s")));
      final long last = datastore.put(SETTINGS, Versions.ANY, Key.from("retries"), new Value("3"));

      datastore.delete(SETTINGS, last);

      final HeadObjectResponse head = head(settingsKey);
      assertThat(head.contentLength()).isEqualTo(SetDocument.MARKER_BYTES);
      assertThat(head.metadata())
        .containsEntry(S3ConfigDatastore.VERSION_METADATA, Long.toString(last))
        .containsEntry(S3ConfigDatastore.STATE_METADATA, "deleted");
      assertThat(datastore.load(SETTINGS)).isEmpty();
      assertThat(datastore.applications()).isEmpty();
    }

    @Test
    void recreatingOverwritesTheMarker() {
      final long first = datastore.replace(SETTINGS, Versions.NONE, Map.of());
      datastore.delete(SETTINGS, first);

      final long second = datastore.replace(SETTINGS, Versions.NONE, Map.of(TIMEOUT, new Value("1s")));

      assertThat(second).isEqualTo(first + 1);
      assertThat(head(settingsKey).metadata()).containsEntry(S3ConfigDatastore.STATE_METADATA, "live");
    }

    @Test
    void foreignObjectsUnderThePrefixAreIgnoredByListings() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of());
      for (final String key : List.of("sets/notours", "sets/billing/notours", "sets/billing/prod/other",
        "sets/Billing/prod/settings", "sets/billing/prod/settings/extra", "elsewhere/billing/prod/settings")) {
        client.putObject(r -> r.bucket(RustFs.BUCKET).key(prefix + key), RequestBody.fromString("junk that is long"));
      }

      assertThat(datastore.applications()).containsExactly(BILLING);
      assertThat(datastore.environments(BILLING)).containsExactly(PROD);
      assertThat(datastore.types(BILLING, PROD)).containsExactly(SetType.SETTINGS);
    }

    @Test
    void anUnreadableObjectIsADatastoreError() {
      client.putObject(r -> r.bucket(RustFs.BUCKET).key(settingsKey)
        .metadata(Map.of(S3ConfigDatastore.VERSION_METADATA, "1")), RequestBody.fromString("not json"));

      assertThatThrownBy(() -> datastore.load(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("sets/billing/prod/settings");
    }

    @Test
    void missingOrInvalidVersionMetadataIsADatastoreError() {
      client.putObject(r -> r.bucket(RustFs.BUCKET).key(settingsKey), RequestBody.fromString("{}"));
      assertThatThrownBy(() -> datastore.version(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("config-version");

      client.putObject(r -> r.bucket(RustFs.BUCKET).key(settingsKey)
        .metadata(Map.of(S3ConfigDatastore.VERSION_METADATA, "abc")), RequestBody.fromString("{}"));
      assertThatThrownBy(() -> datastore.load(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("abc");
    }

    @Test
    void datastoresWithDifferentPrefixesAreSeparate() {
      final S3ConfigDatastore other = new S3ConfigDatastore(client, RustFs.BUCKET, RustFs.freshPrefix(), 1024);
      datastore.replace(SETTINGS, Versions.NONE, Map.of(Key.from("a"), new Value("b")));

      assertThat(other.load(SETTINGS)).isEmpty();
      assertThat(other.applications()).isEmpty();
    }

    @Test
    void namesRoundTripThroughListings() {
      final SetId id = SetId.settings(Application.from("billing-api_2"), Environment.from("us-east-1"));
      datastore.put(id, Versions.NONE, TIMEOUT, new Value("1s"));

      assertThat(datastore.applications()).containsExactly(id.application());
      assertThat(datastore.environments(id.application())).containsExactly(id.environment());
    }

    @Test
    void listingsReadPastOnePageOfResults() {
      for (int i = 0; i < 1005; i++) {
        datastore.put(SetId.metadata(Application.from("app-" + i), PROD), Versions.NONE, Key.from("k"), new Value("v"));
      }

      assertThat(datastore.applications()).hasSize(1005);
    }
  }

  /** Races forced at an exact point, so they happen every run instead of once in a while. */
  @Nested
  class Races {

    /**
     * The bug the marker design fixes: between this writer's read and its write, another writer
     * creates the set and deletes it again. This writer's create must not succeed with the
     * version the other writer already used.
     */
    @Test
    void aCreateRacingACreateAndDeleteNeverReusesAVersion() {
      final AtomicLong theirs = new AtomicLong();
      final AtomicBoolean raced = new AtomicBoolean();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey) && raced.compareAndSet(false, true)) {
          theirs.set(datastore.put(SETTINGS, Versions.NONE, TIMEOUT, new Value("theirs")));
          datastore.delete(SETTINGS, theirs.get());
        }
        return proceed.with(args);
      });

      final long version = mine.put(SETTINGS, Versions.ANY, TIMEOUT, new Value("mine"));

      assertThat(raced).isTrue();
      assertThat(version).isGreaterThan(theirs.get());
      assertThat(datastore.find(SETTINGS, TIMEOUT)).contains(new Value("mine"));
    }

    @Test
    void aRecreateRacingARecreateAndDeleteNeverReusesAVersion() {
      final long old = datastore.replace(SETTINGS, Versions.NONE, Map.of());
      datastore.delete(SETTINGS, old);
      final AtomicLong theirs = new AtomicLong();
      final AtomicBoolean raced = new AtomicBoolean();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey) && raced.compareAndSet(false, true)) {
          theirs.set(datastore.put(SETTINGS, Versions.NONE, TIMEOUT, new Value("theirs")));
          datastore.delete(SETTINGS, theirs.get());
        }
        return proceed.with(args);
      });

      final long version = mine.put(SETTINGS, Versions.NONE, TIMEOUT, new Value("mine"));

      assertThat(theirs.get()).isGreaterThan(old);
      assertThat(version).isGreaterThan(theirs.get());
    }

    @Test
    void anInFlightConflictIsRetriedNotReportedAsAConflict() {
      final long current = datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final AtomicInteger puts = new AtomicInteger();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey) && puts.incrementAndGet() == 1) {
          throw error(409, "ConditionalRequestConflict");
        }
        return proceed.with(args);
      });

      assertThat(mine.put(SETTINGS, current, TIMEOUT, new Value("1s"))).isEqualTo(current + 1);
      assertThat(puts).hasValue(2);
    }

    @Test
    void aLostRaceReportsTheVersionThatWon() {
      final long current = datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final AtomicBoolean raced = new AtomicBoolean();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey) && raced.compareAndSet(false, true)) {
          datastore.put(SETTINGS, Versions.ANY, TIMEOUT, new Value("theirs"));
        }
        return proceed.with(args);
      });

      assertThatThrownBy(() -> mine.put(SETTINGS, current, TIMEOUT, new Value("mine")))
        .isInstanceOfSatisfying(VersionConflictException.class, e -> {
          assertThat(e.expectedVersion()).isEqualTo(current);
          assertThat(e.actualVersion()).isEqualTo(current + 1);
        });
    }

    @Test
    void aDeleteThatLosesARaceRetriesWhenIgnoringVersions() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final AtomicBoolean raced = new AtomicBoolean();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey) && raced.compareAndSet(false, true)) {
          datastore.put(SETTINGS, Versions.ANY, TIMEOUT, new Value("theirs"));
        }
        return proceed.with(args);
      });

      assertThat(mine.delete(SETTINGS, Versions.ANY)).isTrue();
      assertThat(datastore.load(SETTINGS)).isEmpty();
    }

    @Test
    void aWriterThatKeepsLosingGivesUpAfterTheAttemptLimit() {
      datastore.replace(SETTINGS, Versions.NONE, Map.of());
      final AtomicInteger puts = new AtomicInteger();
      final S3ConfigDatastore mine = intercepted((method, args, proceed) -> {
        if (is(method, args, "putObject", settingsKey)) {
          puts.incrementAndGet();
          throw error(409, "ConditionalRequestConflict");
        }
        return proceed.with(args);
      });

      assertThatThrownBy(() -> mine.put(SETTINGS, Versions.ANY, TIMEOUT, new Value("1s")))
        .isInstanceOf(ConfigDatastoreException.class)
        .isNotInstanceOf(VersionConflictException.class)
        .hasMessageContaining("Gave up");
      assertThat(puts).hasValue(S3ConfigDatastore.MAX_ATTEMPTS);
    }
  }

  @Nested
  class MissingBucket {

    private final S3ConfigDatastore unchecked =
      new S3ConfigDatastore(RustFs.client(), "no-such-bucket", "config/", 1024);

    @Test
    void readsFailInsteadOfLookingEmpty() {
      assertThatThrownBy(() -> unchecked.load(SETTINGS)).isInstanceOf(ConfigDatastoreException.class);
      assertThatThrownBy(() -> unchecked.version(SETTINGS)).isInstanceOf(ConfigDatastoreException.class);
      assertThatThrownBy(unchecked::applications).isInstanceOf(ConfigDatastoreException.class);
    }

    @Test
    void writesFailInsteadOfLookingLikeAConflictOrAMissingSet() {
      assertThatThrownBy(() -> unchecked.put(SETTINGS, Versions.ANY, Key.from("a"), new Value("b")))
        .isInstanceOf(ConfigDatastoreException.class)
        .isNotInstanceOf(VersionConflictException.class);
      assertThatThrownBy(() -> unchecked.delete(SETTINGS, Versions.ANY))
        .isInstanceOf(ConfigDatastoreException.class);
    }

    @Test
    void validationReportsTheBucketAndStops() {
      final ValidationResult result = unchecked.validate(Access.READ_WRITE);

      assertThat(result.ok()).isFalse();
      assertThat(result.checks()).hasSize(1);
      assertThat(result.failures().getFirst().name()).isEqualTo("bucket");
      assertThat(result.failures().getFirst().detail()).contains("does not exist");
      assertThatThrownBy(result::requireOk).isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("bucket");
    }
  }

  @Nested
  class Validation {

    private boolean isProbe(final Object[] args) {
      return args[0] instanceof software.amazon.awssdk.core.SdkRequest request
        && request.getValueForField("Key", String.class).map(key -> key.startsWith(prefix + "_validate/")).orElse(false);
    }

    @Test
    void aCorrectlyConfiguredStorePassesEveryCheckAndCleansUp() {
      final ValidationResult result = datastore.validate(Access.READ_WRITE);

      assertThat(result.failures()).isEmpty();
      assertThat(result.checks()).extracting(ValidationResult.Check::name).contains(
        "bucket", "s3:ListBucket", "s3:GetObject", "s3:PutObject",
        "conditional create (If-None-Match)", "conditional update (If-Match)", "probe cleanup");
      assertThat(result.checks()).allMatch(check -> check.status() == ValidationResult.Status.PASSED);
      assertThat(client.listObjectsV2(r -> r.bucket(RustFs.BUCKET).prefix(prefix)).contents()).isEmpty();
      assertThat(result.datastore()).isEqualTo("s3://" + RustFs.BUCKET + "/" + prefix);
    }

    @Test
    void readValidationMakesNoWrites() {
      final AtomicInteger writes = new AtomicInteger();
      final S3ConfigDatastore watched = intercepted((method, args, proceed) -> {
        if (method.getName().startsWith("put") || method.getName().startsWith("delete")) {
          writes.incrementAndGet();
        }
        return proceed.with(args);
      });

      assertThat(watched.validate(Access.READ).ok()).isTrue();
      assertThat(writes).hasValue(0);
    }

    @Test
    void aReadOnlyIdentityPassesReadButFailsReadWrite() {
      final S3ConfigDatastore readOnly = intercepted((method, args, proceed) -> {
        if (method.getName().equals("putObject")) {
          throw error(403, "AccessDenied");
        }
        return proceed.with(args);
      });

      assertThat(readOnly.validate(Access.READ).ok()).isTrue();
      final ValidationResult result = readOnly.validate(Access.READ_WRITE);
      assertThat(result.failures()).extracting(ValidationResult.Check::name).containsExactly("s3:PutObject");
      assertThat(result.failures().getFirst().detail()).contains("s3:PutObject");
    }

    @Test
    void deniedReadsAreReportedWithTheListBucketHint() {
      final S3ConfigDatastore denied = intercepted((method, args, proceed) -> {
        if (method.getName().equals("getObjectAsBytes")) {
          throw error(403, "AccessDenied");
        }
        return proceed.with(args);
      });

      final ValidationResult result = denied.validate(Access.READ);
      assertThat(result.failures()).extracting(ValidationResult.Check::name).containsExactly("s3:GetObject");
      assertThat(result.failures().getFirst().detail()).contains("s3:ListBucket");
    }

    @Test
    void aStoreThatIgnoresConditionalWritesFails() {
      final S3ConfigDatastore unconditional = intercepted((method, args, proceed) -> {
        if (method.getName().equals("putObject") && isProbe(args)) {
          args[0] = ((PutObjectRequest) args[0]).toBuilder().ifNoneMatch(null).ifMatch(null).build();
        }
        return proceed.with(args);
      });

      final ValidationResult result = unconditional.validate(Access.READ_WRITE);

      assertThat(result.failures()).extracting(ValidationResult.Check::name)
        .containsExactly("conditional create (If-None-Match)", "conditional update (If-Match)");
    }

    @Test
    void anUncleanableProbeIsOnlyAWarning() {
      final S3ConfigDatastore noDelete = intercepted((method, args, proceed) -> {
        if (method.getName().equals("deleteObject")) {
          throw error(403, "AccessDenied");
        }
        return proceed.with(args);
      });

      final ValidationResult result = noDelete.validate(Access.READ_WRITE);

      assertThat(result.ok()).isTrue();
      assertThat(result.checks()).filteredOn(check -> check.status() == ValidationResult.Status.WARNING)
        .extracting(ValidationResult.Check::name).containsExactly("probe cleanup");
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
      datastore.replace(SETTINGS, Versions.NONE, Map.of(TIMEOUT, new Value("30s")));

      try (ConfigDatastore opened = ConfigDatastores.open("s3", options(Map.of("max-set-bytes", "2048")))) {
        assertThat(opened).isInstanceOf(S3ConfigDatastore.class);
        assertThat(opened.maxSetBytes()).isEqualTo(2048);
        assertThat(opened.find(SETTINGS, TIMEOUT)).contains(new Value("30s"));
        assertThat(opened.validate(Access.READ_WRITE).ok()).isTrue();
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
    void invalidOptionValuesAreRejectedByName() {
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("path-style", "yes"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("path-style");
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("max-set-bytes", "lots"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("max-set-bytes");
      assertThatThrownBy(() -> ConfigDatastores.open("s3", options(Map.of("endpoint", "http://bad host"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("endpoint");
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
