package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.Changes;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A {@link ConfigDatastore} that stores each set as one JSON object in an S3 bucket.
 *
 * <p>Every write is a single conditional request, so it is atomic: a new set is created with
 * {@code If-None-Match: *}, an existing one is replaced with {@code If-Match} on the ETag read
 * just before, and a set is deleted with a conditional delete. A deleted set leaves a small
 * tombstone object holding its last version, so a set created again continues past it. See
 * {@link S3Keys} for the layout.
 *
 * <p>Requires an S3 service that supports conditional writes and conditional deletes: Amazon S3,
 * or RustFS 1.0.4 and later.
 */
public final class S3ConfigDatastore implements ConfigDatastore {

  /**
   * The size limit used when none is given: 1 MiB. S3 itself allows far larger objects, but every
   * read and write moves the whole set.
   */
  public static final long DEFAULT_MAX_SET_BYTES = 1024L * 1024L;

  /** The object metadata holding the set's version, so {@link #version} needs only a HEAD. */
  static final String VERSION_METADATA = "config-version";

  /** How many times a write retries after losing a race, when it was told to ignore versions. */
  static final int MAX_ATTEMPTS = 50;

  /** The longest pause between retries; pauses start short and double, with random jitter. */
  private static final long MAX_BACKOFF_MILLIS = 200;

  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String NO_SUCH_BUCKET = "NoSuchBucket";

  private final S3Client s3;
  private final String bucket;
  private final S3Keys keys;
  private final long maxSetBytes;
  private final boolean closeClient;

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
    return s3Call("load " + id, () -> get(keys.live(id))
      .map(stored -> new Snapshot(id, stored.document().values(), stored.document().version())));
  }

  @Override
  public long version(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return s3Call("read the version of " + id, () -> head(keys.live(id)).map(Head::version).orElse(Versions.NONE));
  }

  @Override
  public Set<Application> applications() {
    return s3Call("list applications", () -> {
      final Set<Application> result = new HashSet<>();
      commonPrefixes(keys.applicationsPrefix())
        .forEach(name -> result.add(Application.from(name)));
      return Set.copyOf(result);
    });
  }

  @Override
  public Set<Environment> environments(final Application application) {
    Objects.requireNonNull(application, "application must not be null");
    return s3Call("list environments of " + application, () -> {
      final Set<Environment> result = new HashSet<>();
      commonPrefixes(keys.applicationPrefix(application))
        .forEach(name -> result.add(Environment.from(name)));
      return Set.copyOf(result);
    });
  }

  @Override
  public Set<SetType> types(final Application application, final Environment environment) {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    return s3Call("list set types of " + application + "/" + environment, () -> {
      final String parent = keys.environmentPrefix(application, environment);
      final Set<SetType> result = EnumSet.noneOf(SetType.class);
      final ListObjectsV2Request request = ListObjectsV2Request.builder()
        .bucket(bucket).prefix(parent).delimiter("/").build();
      for (final S3Object object : s3.listObjectsV2Paginator(request).contents()) {
        keys.typeIn(parent, object.key()).ifPresent(result::add);
      }
      return Set.copyOf(result);
    });
  }

  // Writing

  @Override
  public long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    Objects.requireNonNull(id, "id must not be null");
    final List<Change> validated = Changes.validate(changes);
    Versions.validateExpected(expectedVersion);
    return s3Call("write " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final Optional<Stored> live = get(keys.live(id));
        final long current = live.map(stored -> stored.document().version()).orElse(Versions.NONE);
        if (!Versions.matches(expectedVersion, current)) {
          throw new VersionConflictException(id, expectedVersion, current);
        }
        final Map<Key, Value> before = live.map(stored -> stored.document().values()).orElse(Map.of());
        final Map<Key, Value> after = Changes.apply(before, validated);
        final long bytes = SetSize.bytes(after);
        if (bytes > maxSetBytes) {
          throw new SetTooLargeException(id, bytes, maxSetBytes);
        }
        // A new set continues past the version its tombstone remembers, if any.
        final long base = live.isPresent()
          ? current
          : head(keys.tombstone(id)).map(Head::version).orElse(Versions.NONE);
        final long next = base + 1;
        final PutObjectRequest.Builder put = putRequest(keys.live(id), next);
        if (live.isPresent()) {
          put.ifMatch(live.get().etag());
        } else {
          put.ifNoneMatch("*");
        }
        try {
          s3.putObject(put.build(), RequestBody.fromBytes(new SetDocument(next, after).toJson()));
          return next;
        } catch (final S3Exception e) {
          if (!lostRace(e)) {
            throw e;
          }
          if (expectedVersion != Versions.ANY) {
            throw new VersionConflictException(id, expectedVersion, version(id));
          }
          backOff(attempt); // told to ignore versions: read again and retry
        }
      }
      throw new ConfigDatastoreException("Gave up writing " + id + " after " + MAX_ATTEMPTS
        + " attempts: other writers kept changing it");
    });
  }

  @Override
  public boolean delete(final SetId id, final long expectedVersion) {
    Objects.requireNonNull(id, "id must not be null");
    Versions.validateExpected(expectedVersion);
    return s3Call("delete " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final Optional<Head> live = head(keys.live(id));
        final long current = live.map(Head::version).orElse(Versions.NONE);
        if (!Versions.matches(expectedVersion, current)) {
          throw new VersionConflictException(id, expectedVersion, current);
        }
        if (live.isEmpty()) {
          return false;
        }
        // Record the version first, so it survives the delete. The tombstone write is
        // conditional too, so a delayed deleter can never lower a newer tombstone.
        if (!raiseTombstone(id, current)) {
          backOff(attempt);
          continue;
        }
        try {
          s3.deleteObject(request -> request.bucket(bucket).key(keys.live(id)).ifMatch(live.get().etag()));
          return true;
        } catch (final S3Exception e) {
          if (!lostRace(e)) {
            throw e;
          }
          if (expectedVersion != Versions.ANY) {
            throw new VersionConflictException(id, expectedVersion, version(id));
          }
          backOff(attempt);
        }
      }
      throw new ConfigDatastoreException("Gave up deleting " + id + " after " + MAX_ATTEMPTS
        + " attempts: other writers kept changing it");
    });
  }

  @Override
  public long maxSetBytes() {
    return maxSetBytes;
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

  /** Makes the tombstone hold at least {@code version}. Returns false if it lost a race and should retry. */
  private boolean raiseTombstone(final SetId id, final long version) {
    final String key = keys.tombstone(id);
    final Optional<Head> tombstone = head(key);
    if (tombstone.isPresent() && tombstone.get().version() >= version) {
      return true;
    }
    final PutObjectRequest.Builder put = putRequest(key, version);
    if (tombstone.isPresent()) {
      put.ifMatch(tombstone.get().etag());
    } else {
      put.ifNoneMatch("*");
    }
    try {
      s3.putObject(put.build(), RequestBody.fromBytes(new SetDocument(version, Map.of()).toJson()));
      return true;
    } catch (final S3Exception e) {
      if (lostRace(e)) {
        return false;
      }
      throw e;
    }
  }

  private PutObjectRequest.Builder putRequest(final String key, final long version) {
    return PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .contentType(JSON_CONTENT_TYPE)
      .metadata(Map.of(VERSION_METADATA, Long.toString(version)));
  }

  private Optional<Stored> get(final String key) {
    try {
      final ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key));
      return Optional.of(new Stored(response.response().eTag(), SetDocument.fromJson(response.asByteArray(), key)));
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
      final String version = response.metadata().get(VERSION_METADATA);
      if (version == null) {
        throw new ConfigDatastoreException("Object " + key + " has no " + VERSION_METADATA + " metadata");
      }
      return Optional.of(new Head(response.eTag(), Long.parseLong(version)));
    } catch (final S3Exception e) {
      // A HEAD response has no body, so a missing bucket cannot be told apart from a missing
      // object here; the provider checks the bucket when it opens the datastore.
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private List<String> commonPrefixes(final String parent) {
    final ListObjectsV2Request request = ListObjectsV2Request.builder()
      .bucket(bucket).prefix(parent).delimiter("/").build();
    return s3.listObjectsV2Paginator(request).commonPrefixes().stream()
      .map(CommonPrefix::prefix)
      .map(prefix -> keys.nameIn(parent, prefix))
      .flatMap(Optional::stream)
      .toList();
  }

  /**
   * Whether a conditional request failed because another writer got there first: the object
   * changed (412), appeared (412), disappeared (404), or a write was in flight (409).
   */
  private static boolean lostRace(final S3Exception e) {
    return switch (e.statusCode()) {
      case 412, 409 -> true;
      case 404 -> !NO_SUCH_BUCKET.equals(errorCode(e));
      default -> false;
    };
  }

  private static String errorCode(final S3Exception e) {
    return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
  }

  /** Pauses before a retry: up to 5ms, then 10ms, 20ms... capped at {@link #MAX_BACKOFF_MILLIS}, randomised. */
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
    } catch (final SdkException e) {
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("Could not " + action + ": invalid " + VERSION_METADATA + " metadata", e);
    }
  }

  /** A set object as read, with the ETag to make the next write conditional on. */
  private record Stored(String etag, SetDocument document) {
  }

  /** A set or tombstone object's ETag and version, from a HEAD. */
  private record Head(String etag, long version) {
  }
}
