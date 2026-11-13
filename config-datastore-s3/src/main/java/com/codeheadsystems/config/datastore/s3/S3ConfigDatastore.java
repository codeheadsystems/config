package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.Changes;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetSize;
import com.codeheadsystems.config.datastore.SetTooLargeException;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.ValidationResult.Check;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A {@link ConfigDatastore} that stores each set as one object in an S3 bucket (see {@link S3Keys}
 * for the layout and {@link SetDocument} for the format).
 *
 * <p>A set's key is never removed once written. Deleting a set overwrites it with a small marker
 * that keeps its version, and creating it again overwrites the marker. So every write, whether a
 * create, an update or a delete, is one conditional request on that one key: {@code If-Match} on
 * the ETag read just before, or {@code If-None-Match: *} for a key never used. Any other write in
 * between changes the ETag and makes the request fail, so writes are atomic and a version is never
 * reused, even across a delete.
 *
 * <p>A write that loses such a race starts again from a fresh read: with a specific expected
 * version that ends in a {@link VersionConflictException} carrying the real current version; with
 * {@link Versions#ANY} it retries, with backoff, up to {@link #MAX_ATTEMPTS} times.
 *
 * <p>Requires an S3 service that supports conditional writes ({@code If-Match} and
 * {@code If-None-Match} on PutObject): Amazon S3, or RustFS 1.0.4 and later. {@link
 * #validate(Access)} checks this, along with the bucket and the permissions: readers need
 * {@code s3:ListBucket} and {@code s3:GetObject}; writers also need {@code s3:PutObject}.
 * Without {@code s3:ListBucket}, Amazon S3 answers 403 instead of 404 for a missing set.
 */
public final class S3ConfigDatastore implements ConfigDatastore {

  /**
   * The size limit used when none is given: 1 MiB. S3 itself allows far larger objects, but every
   * read and write moves the whole set.
   */
  public static final long DEFAULT_MAX_SET_BYTES = 1024L * 1024L;

  /** How many times a write retries after losing a race, when it was told to ignore versions. */
  static final int MAX_ATTEMPTS = 50;

  /** The object metadata holding the set's version, so {@link #version} needs only a HEAD. */
  static final String VERSION_METADATA = "config-version";

  /** The object metadata saying whether the object is a live set or a deleted-set marker. */
  static final String STATE_METADATA = "config-state";

  private static final String LIVE = "live";
  private static final String DELETED = "deleted";

  /** The longest pause between retries. */
  private static final long MAX_BACKOFF_MILLIS = 200;

  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String NO_SUCH_BUCKET = "NoSuchBucket";

  private final S3Client s3;
  private final String bucket;
  private final S3Keys keys;
  private final long maxSetBytes;
  private final boolean closeClient;
  // A HEAD answers 404 with no body for a missing bucket as well as a missing object; the first
  // such answer checks the bucket once, so a wrong bucket is an error, not an empty datastore.
  private volatile boolean bucketConfirmed;

  /**
   * Creates a datastore over a bucket. The caller keeps ownership of the client: {@link #close()}
   * does not close it.
   *
   * @param s3          the S3 client.
   * @param bucket      the bucket, which must already exist.
   * @param prefix      the key prefix under which every object is stored, such as {@code config/};
   *                    a trailing {@code /} is added if missing, and an empty prefix uses the bucket root.
   * @param maxSetBytes the largest set this datastore stores, at least 1.
   */
  public S3ConfigDatastore(final S3Client s3, final String bucket, final String prefix, final long maxSetBytes) {
    this(s3, bucket, prefix, maxSetBytes, false);
  }

  S3ConfigDatastore(final S3Client s3, final String bucket, final String prefix, final long maxSetBytes,
                    final boolean closeClient) {
    this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
    this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
    this.keys = new S3Keys(prefix);
    if (maxSetBytes < 1) {
      throw new IllegalArgumentException("maxSetBytes must be at least 1: " + maxSetBytes);
    }
    this.maxSetBytes = maxSetBytes;
    this.closeClient = closeClient;
  }

  // Reading

  @Override
  public Optional<Snapshot> load(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return s3Call("load " + id, () -> get(keys.set(id))
      .filter(Stored::live)
      .map(stored -> new Snapshot(id, stored.values(), stored.version())));
  }

  @Override
  public long version(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return s3Call("read the version of " + id, () -> head(keys.set(id))
      .filter(Head::live)
      .map(Head::version)
      .orElse(Versions.NONE));
  }

  @Override
  public Set<Application> applications() {
    return s3Call("list applications", () -> collect(keys.setsPrefix(), SetId::application));
  }

  @Override
  public Set<Environment> environments(final Application application) {
    Objects.requireNonNull(application, "application must not be null");
    return s3Call("list environments of " + application.value(),
      () -> collect(keys.applicationPrefix(application), SetId::environment));
  }

  @Override
  public Set<SetType> types(final Application application, final Environment environment) {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    return s3Call("list set types of " + application.value() + "/" + environment.value(), () -> {
      final Set<SetType> result = EnumSet.noneOf(SetType.class);
      liveSets(keys.environmentPrefix(application, environment)).forEach(id -> result.add(id.type()));
      return Set.copyOf(result);
    });
  }

  // Writing

  @Override
  public long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    Objects.requireNonNull(id, "id must not be null");
    final List<Change> validated = Changes.validate(changes);
    Versions.validateExpected(expectedVersion);
    final String key = keys.set(id);
    return s3Call("write " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final Optional<Stored> stored = get(key);
        final long current = stored.filter(Stored::live).map(Stored::version).orElse(Versions.NONE);
        if (!Versions.matches(expectedVersion, current)) {
          throw new VersionConflictException(id, expectedVersion, current);
        }
        final Map<Key, Value> before = stored.filter(Stored::live).map(Stored::values).orElse(Map.of());
        final Map<Key, Value> after = Changes.apply(before, validated);
        final long bytes = SetSize.bytes(after);
        if (bytes > maxSetBytes) {
          throw new SetTooLargeException(id, bytes, maxSetBytes);
        }
        // Continue from whatever the key last held: the live version, or a deleted set's marker.
        final long next = stored.map(Stored::version).orElse(Versions.NONE) + 1;
        if (conditionalPut(key, stored.map(Stored::etag), next, LIVE, new SetDocument(next, after).toJson())) {
          return next;
        }
        backOff(attempt); // lost a race: start again from a fresh read
      }
      throw new ConfigDatastoreException("Gave up writing " + id + " after " + MAX_ATTEMPTS
        + " attempts: other writers kept changing it");
    });
  }

  @Override
  public boolean delete(final SetId id, final long expectedVersion) {
    Objects.requireNonNull(id, "id must not be null");
    Versions.validateExpected(expectedVersion);
    final String key = keys.set(id);
    return s3Call("delete " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final Optional<Head> head = head(key);
        final long current = head.filter(Head::live).map(Head::version).orElse(Versions.NONE);
        if (!Versions.matches(expectedVersion, current)) {
          throw new VersionConflictException(id, expectedVersion, current);
        }
        if (current == Versions.NONE) {
          return false;
        }
        // The marker keeps the version, so a set created again continues past it.
        if (conditionalPut(key, head.map(Head::etag), current, DELETED, SetDocument.marker(current))) {
          return true;
        }
        backOff(attempt);
      }
      throw new ConfigDatastoreException("Gave up deleting " + id + " after " + MAX_ATTEMPTS
        + " attempts: other writers kept changing it");
    });
  }

  @Override
  public long maxSetBytes() {
    return maxSetBytes;
  }

  // Validation

  @Override
  public ValidationResult validate(final Access access) {
    Objects.requireNonNull(access, "access must not be null");
    return new S3Validation(s3, bucket, keys).run(access, "s3://" + bucket + "/" + keys.prefix());
  }

  /**
   * Closes the S3 client if this datastore created it (through {@link S3ConfigDatastoreProvider}).
   */
  @Override
  public void close() {
    if (closeClient) {
      s3.close();
    }
  }

  // S3 helpers

  /**
   * Writes an object only if the key still holds what was read: the given ETag, or nothing.
   *
   * @return true if written; false if another writer got there first.
   */
  private boolean conditionalPut(final String key, final Optional<String> etag, final long version,
                                 final String state, final byte[] body) {
    final PutObjectRequest.Builder put = PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .contentType(JSON_CONTENT_TYPE)
      .metadata(Map.of(VERSION_METADATA, Long.toString(version), STATE_METADATA, state));
    if (etag.isPresent()) {
      put.ifMatch(etag.get());
    } else {
      put.ifNoneMatch("*");
    }
    try {
      s3.putObject(put.build(), RequestBody.fromBytes(body));
      return true;
    } catch (final S3Exception e) {
      if (lostRace(e)) {
        return false;
      }
      throw e;
    }
  }

  private Optional<Stored> get(final String key) {
    try {
      final ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key));
      final Map<String, String> metadata = response.response().metadata();
      final long version = versionOf(metadata, key);
      final String etag = response.response().eTag();
      if (DELETED.equals(metadata.get(STATE_METADATA))) {
        return Optional.of(new Stored(etag, version, false, Map.of()));
      }
      final SetDocument document = SetDocument.fromJson(response.asByteArray(), key);
      return Optional.of(new Stored(etag, document.version(), true, document.values()));
    } catch (final S3Exception e) {
      if (e.statusCode() == 404 && !NO_SUCH_BUCKET.equals(errorCode(e))) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private Optional<Head> head(final String key) {
    try {
      final HeadObjectResponse response = s3.headObject(request -> request.bucket(bucket).key(key));
      final Map<String, String> metadata = response.metadata();
      return Optional.of(new Head(response.eTag(), versionOf(metadata, key),
        !DELETED.equals(metadata.get(STATE_METADATA))));
    } catch (final S3Exception e) {
      if (e.statusCode() == 404) {
        confirmBucket();
        return Optional.empty();
      }
      throw e;
    }
  }

  /** Throws if the bucket does not exist; checked once, on the first 404 from a HEAD. */
  private void confirmBucket() {
    if (!bucketConfirmed) {
      s3.headBucket(request -> request.bucket(bucket)); // throws S3Exception 404 if missing
      bucketConfirmed = true;
    }
  }

  private static long versionOf(final Map<String, String> metadata, final String key) {
    final String version = metadata.get(VERSION_METADATA);
    if (version == null) {
      throw new ConfigDatastoreException("Object " + key + " has no " + VERSION_METADATA + " metadata");
    }
    try {
      final long parsed = Long.parseLong(version);
      if (parsed < 1) {
        throw new NumberFormatException("version below 1");
      }
      return parsed;
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("Object " + key + " has invalid " + VERSION_METADATA
        + " metadata '" + version + "'", e);
    }
  }

  /** The live sets listed under a prefix; deleted-set markers are recognised by their size. */
  private List<SetId> liveSets(final String parent) {
    final ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(parent).build();
    final List<SetId> result = new ArrayList<>();
    for (final S3Object object : s3.listObjectsV2Paginator(request).contents()) {
      if (object.size() != null && object.size() != SetDocument.MARKER_BYTES) {
        keys.parse(object.key()).ifPresent(result::add);
      }
    }
    return result;
  }

  private <T> Set<T> collect(final String parent, final Function<SetId, T> part) {
    final Set<T> result = new HashSet<>();
    liveSets(parent).forEach(id -> result.add(part.apply(id)));
    return Set.copyOf(result);
  }

  /**
   * Whether a conditional request failed because another writer got there first: the object
   * changed or appeared (412), was removed from outside (404), or a write was in flight (409).
   */
  private static boolean lostRace(final S3Exception e) {
    return switch (e.statusCode()) {
      case 412, 409 -> true;
      case 404 -> !NO_SUCH_BUCKET.equals(errorCode(e));
      default -> false;
    };
  }

  static String errorCode(final S3Exception e) {
    return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
  }

  /**
   * Pauses before a retry. The ceiling starts at 5ms and doubles each attempt (5, 10, 20, 40, 80,
   * 160ms) up to {@link #MAX_BACKOFF_MILLIS}; the pause is random below it, so racing writers
   * spread out.
   */
  private static void backOff(final int attempt) {
    final long ceiling = Math.min(MAX_BACKOFF_MILLIS, 5L << Math.min(attempt, 10));
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConfigDatastoreException("Interrupted while retrying a write", e);
    }
  }

  private static <T> T s3Call(final String action, final Supplier<T> call) {
    try {
      return call.get();
    } catch (final S3Exception e) {
      if (e.statusCode() == 403) {
        throw new ConfigDatastoreException("Could not " + action + ": access denied (" + e.getMessage()
          + "); run validate() to see which permission is missing", e);
      }
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    } catch (final SdkException e) {
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    }
  }

  /** A set object as read; a deleted-set marker has {@code live} false and no values. */
  private record Stored(String etag, long version, boolean live, Map<Key, Value> values) {
  }

  /** A set object's ETag, version and state, from a HEAD. */
  private record Head(String etag, long version, boolean live) {
  }

  /** The checks behind {@link #validate(Access)}. */
  private record S3Validation(S3Client s3, String bucket, S3Keys keys) {

    ValidationResult run(final Access access, final String description) {
      final List<Check> checks = new ArrayList<>();
      final Check bucketCheck = check("bucket", () -> {
        s3.headBucket(request -> request.bucket(bucket));
        return "bucket '" + bucket + "' exists and is reachable";
      }, e -> e.statusCode() == 404
        ? "bucket '" + bucket + "' does not exist; create it first"
        : "cannot reach bucket '" + bucket + "' (HTTP " + e.statusCode() + "); HeadBucket needs s3:ListBucket");
      checks.add(bucketCheck);
      if (bucketCheck.status() == ValidationResult.Status.FAILED) {
        return new ValidationResult(description, access, checks); // nothing else can work
      }
      checks.add(check("s3:ListBucket", () -> {
        s3.listObjectsV2(request -> request.bucket(bucket).prefix(keys.prefix()).maxKeys(1));
        return "can list objects under '" + keys.prefix() + "'";
      }, e -> "cannot list objects (HTTP " + e.statusCode() + "); listings need s3:ListBucket"));
      checks.add(check("s3:GetObject", () -> {
        try {
          s3.getObjectAsBytes(request -> request.bucket(bucket).key(keys.probe()));
          return "can read objects";
        } catch (final S3Exception e) {
          if (e.statusCode() == 404) {
            return "can read objects, and a missing object reads as missing";
          }
          throw e;
        }
      }, e -> e.statusCode() == 403
        ? "reading a missing object was denied (HTTP 403); grant s3:GetObject, and s3:ListBucket so a "
          + "missing set reads as missing rather than denied"
        : "cannot read objects (HTTP " + e.statusCode() + ")"));
      if (access == Access.READ_WRITE) {
        writeChecks(checks);
      }
      return new ValidationResult(description, access, checks);
    }

    private void writeChecks(final List<Check> checks) {
      final String probe = keys.probe();
      final String[] etag = new String[1];
      final Check put = check("s3:PutObject", () -> {
        etag[0] = s3.putObject(request -> request.bucket(bucket).key(probe).ifNoneMatch("*"),
          RequestBody.fromString("probe 1")).eTag();
        return "can write objects";
      }, e -> "cannot write objects (HTTP " + e.statusCode() + "); writers need s3:PutObject");
      checks.add(put);
      if (put.status() == ValidationResult.Status.FAILED) {
        return;
      }
      checks.add(expectRejected("conditional create (If-None-Match)",
        () -> s3.putObject(request -> request.bucket(bucket).key(probe).ifNoneMatch("*"),
          RequestBody.fromString("probe 2")),
        "the store ignored If-None-Match and overwrote an existing object; writes would not be safe"));
      checks.add(expectRejected("conditional update (If-Match)",
        () -> s3.putObject(request -> request.bucket(bucket).key(probe).ifMatch("\"0000000000000000\""),
          RequestBody.fromString("probe 3")),
        "the store ignored a mismatched If-Match and overwrote the object; writes would not be safe"));
      checks.add(check("conditional update with a matching ETag", () -> {
        s3.putObject(request -> request.bucket(bucket).key(probe).ifMatch(etag[0]), RequestBody.fromString("probe 4"));
        return "a matching If-Match is accepted";
      }, e -> "a matching If-Match was rejected (HTTP " + e.statusCode() + "); updates would always fail"));
      try {
        s3.deleteObject(request -> request.bucket(bucket).key(probe));
        checks.add(Check.passed("probe cleanup", "removed the probe object"));
      } catch (final SdkException e) {
        checks.add(Check.warning("probe cleanup", "could not remove probe object " + probe
          + " (the datastore never deletes objects, so s3:DeleteObject is optional): " + e.getMessage()));
      }
    }

    private static Check check(final String name, final Supplier<String> action,
                               final Function<S3Exception, String> onFailure) {
      try {
        return Check.passed(name, action.get());
      } catch (final S3Exception e) {
        return Check.failed(name, onFailure.apply(e));
      } catch (final SdkException e) {
        return Check.failed(name, "could not reach the store: " + e.getMessage());
      }
    }

    private static Check expectRejected(final String name, final Runnable write, final String ifAccepted) {
      try {
        write.run();
        return Check.failed(name, ifAccepted);
      } catch (final S3Exception e) {
        return e.statusCode() == 412
          ? Check.passed(name, "the store enforces it")
          : Check.failed(name, "expected HTTP 412, got " + e.statusCode() + ": " + e.getMessage());
      } catch (final SdkException e) {
        return Check.failed(name, "could not reach the store: " + e.getMessage());
      }
    }
  }
}
