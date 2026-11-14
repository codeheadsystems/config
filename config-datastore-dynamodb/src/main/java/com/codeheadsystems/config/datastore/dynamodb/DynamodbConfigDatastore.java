package com.codeheadsystems.config.datastore.dynamodb;

import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.LIVE;
import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.PARTITION_KEY;
import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.SEPARATOR;
import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.SORT_KEY;
import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.VALUES;
import static com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.VERSION;

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
import com.codeheadsystems.config.datastore.dynamodb.DynamodbConfigTable.SortKey;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * A {@link ConfigDatastore} that stores each set as one item in a DynamoDB table (see
 * {@link DynamodbConfigTable} for the layout). Create the table first, with
 * {@link DynamodbConfigTable#create} or your infrastructure tooling.
 *
 * <p>A set's item is never removed once written. Deleting a set marks it not live and removes its
 * values, keeping its version, and creating it again continues from that version. Every write is
 * one conditional request on that one item, conditioned on the version and live flag read just
 * before (or on the item not existing). That pair never repeats for a set: a write moves it from
 * {@code (v, any)} to {@code (v + 1, live)}, and a delete from {@code (v, live)} to
 * {@code (v, deleted)}. So any write in between makes the request fail, writes are atomic, and a
 * version is never reused, even across a delete.
 *
 * <p>A write that loses such a race starts again from a fresh read: with a specific expected
 * version that ends in a {@link VersionConflictException} carrying the real current version; with
 * {@link Versions#ANY} it retries, with backoff, up to {@link #MAX_ATTEMPTS} times. Every read is
 * strongly consistent.
 *
 * <p>An item is limited to 400 KB, counting each map entry's key, value and a byte of overhead,
 * so {@link #DEFAULT_MAX_SET_BYTES} leaves room for sets of many short keys. Readers need
 * {@code dynamodb:GetItem}, {@code dynamodb:Query} and {@code dynamodb:Scan} on the table;
 * writers also need {@code dynamodb:PutItem} and {@code dynamodb:UpdateItem}.
 * {@link #validate(Access)} checks these, and the table's key schema.
 *
 * <p>For dependency injection, the class is a {@link Singleton} and its {@linkplain Inject
 * injectable} constructor takes the client and the table name, qualified as
 * {@code @Named(}{@value #TABLE}{@code )}. Use {@link #DynamodbConfigDatastore(DynamoDbClient,
 * String, long)} from a provider method to set another size limit. The caller keeps ownership of
 * the client: {@link #close()} does not close it.
 */
@Singleton
public final class DynamodbConfigDatastore implements ConfigDatastore {

  /** The {@link Named} qualifier on the injected table name. */
  public static final String TABLE = "config.datastore.dynamodb.table";

  /**
   * The size limit used when none is given: 256 KiB. In the worst case, a set of three-character
   * keys with empty values, an item is about a third larger than its measured size, which keeps
   * this under DynamoDB's 400 KB item limit.
   */
  public static final long DEFAULT_MAX_SET_BYTES = 256L * 1024L;

  /** How many times a write retries after losing a race, when it was told to ignore versions. */
  static final int MAX_ATTEMPTS = 50;

  /** The partition key of validation probes: not a valid application, so never a set. */
  static final String PROBE_PARTITION = "#probe";

  /** The longest pause between retries. */
  private static final long MAX_BACKOFF_MILLIS = 200;

  private static final String ACCESS_DENIED = "AccessDeniedException";

  private final DynamoDbClient dynamodb;
  private final String table;
  private final long maxSetBytes;
  private final boolean closeClient;

  /**
   * Creates a datastore with the default size limit, {@link #DEFAULT_MAX_SET_BYTES}.
   *
   * @param dynamodb the client.
   * @param table    the table, which must already exist.
   */
  @Inject
  public DynamodbConfigDatastore(final DynamoDbClient dynamodb, @Named(TABLE) final String table) {
    this(dynamodb, table, DEFAULT_MAX_SET_BYTES);
  }

  /**
   * Creates a datastore.
   *
   * @param dynamodb    the client.
   * @param table       the table, which must already exist.
   * @param maxSetBytes the largest set this datastore stores, at least 1.
   */
  public DynamodbConfigDatastore(final DynamoDbClient dynamodb, final String table, final long maxSetBytes) {
    this(dynamodb, table, maxSetBytes, false);
  }

  DynamodbConfigDatastore(final DynamoDbClient dynamodb, final String table, final long maxSetBytes,
                          final boolean closeClient) {
    this.dynamodb = Objects.requireNonNull(dynamodb, "dynamodb must not be null");
    this.table = Objects.requireNonNull(table, "table must not be null");
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
    return call("load " + id, () -> get(id, true)
      .filter(Stored::live)
      .map(stored -> new Snapshot(id, stored.values(), stored.version())));
  }

  @Override
  public Optional<Value> find(final SetId id, final Key key) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(key, "key must not be null");
    // Only live sets have values, so reading the one entry is enough.
    return call("find " + key.name() + " in " + id, () -> {
      final GetItemResponse response = dynamodb.getItem(request -> request
        .tableName(table)
        .key(DynamodbConfigTable.key(id))
        .consistentRead(true)
        .projectionExpression("#values.#name")
        .expressionAttributeNames(Map.of("#values", VALUES, "#name", key.name())));
      return Optional.ofNullable(response.item().get(VALUES))
        .map(values -> values.m().get(key.name()))
        .map(value -> new Value(value.s()));
    });
  }

  @Override
  public long version(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return call("read the version of " + id, () -> get(id, false)
      .filter(Stored::live)
      .map(Stored::version)
      .orElse(Versions.NONE));
  }

  @Override
  public Set<Application> applications() {
    return call("list applications", () -> {
      final ScanRequest request = ScanRequest.builder()
        .tableName(table)
        .consistentRead(true)
        .projectionExpression("#pk")
        .filterExpression("#live = :live")
        .expressionAttributeNames(Map.of("#pk", PARTITION_KEY, "#live", LIVE))
        .expressionAttributeValues(Map.of(":live", AttributeValue.fromBool(true)))
        .build();
      final Set<Application> result = new HashSet<>();
      dynamodb.scanPaginator(request).items()
        .forEach(item -> result.add(Application.from(item.get(PARTITION_KEY).s())));
      return Set.copyOf(result);
    });
  }

  @Override
  public Set<Environment> environments(final Application application) {
    Objects.requireNonNull(application, "application must not be null");
    return call("list environments of " + application.value(),
      () -> collect(application, Optional.empty(), sortKey -> Environment.from(sortKey.environment())));
  }

  @Override
  public Set<SetType> types(final Application application, final Environment environment) {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    return call("list set types of " + application.value() + "/" + environment.value(), () -> {
      final Set<SetType> result = EnumSet.noneOf(SetType.class);
      result.addAll(collect(application, Optional.of(environment), SortKey::type));
      return Set.copyOf(result);
    });
  }

  // Writing

  @Override
  public long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    Objects.requireNonNull(id, "id must not be null");
    final List<Change> validated = Changes.validate(changes);
    Versions.validateExpected(expectedVersion);
    return call("write " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final Optional<Stored> stored = get(id, true);
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
        // Continue from whatever the item last held: the live version, or a deleted set's.
        final long next = stored.map(Stored::version).orElse(Versions.NONE) + 1;
        if (conditionalPut(id, stored, next, after)) {
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
    return call("delete " + id, () -> {
      for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        final long current = get(id, false).filter(Stored::live).map(Stored::version).orElse(Versions.NONE);
        if (!Versions.matches(expectedVersion, current)) {
          throw new VersionConflictException(id, expectedVersion, current);
        }
        if (current == Versions.NONE) {
          return false;
        }
        try {
          // Keeps the version, so a set created again continues past it.
          dynamodb.updateItem(request -> request
            .tableName(table)
            .key(DynamodbConfigTable.key(id))
            .updateExpression("SET #live = :deleted REMOVE #values")
            .conditionExpression("#version = :version AND #live = :live")
            .expressionAttributeNames(Map.of("#live", LIVE, "#values", VALUES, "#version", VERSION))
            .expressionAttributeValues(Map.of(
              ":deleted", AttributeValue.fromBool(false),
              ":live", AttributeValue.fromBool(true),
              ":version", number(current))));
          return true;
        } catch (final ConditionalCheckFailedException e) {
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

  // Validation

  @Override
  public ValidationResult validate(final Access access) {
    Objects.requireNonNull(access, "access must not be null");
    return new DynamodbValidation(dynamodb, table).run(access, "dynamodb://"
      + dynamodb.serviceClientConfiguration().region() + "/" + table);
  }

  /**
   * Closes the client if this datastore created it (through {@link DynamodbConfigDatastoreProvider}).
   */
  @Override
  public void close() {
    if (closeClient) {
      dynamodb.close();
    }
  }

  // DynamoDB helpers

  /**
   * Writes a live set's item only if the item still holds what was read: the same version and
   * live flag, or nothing.
   *
   * @return true if written; false if another writer got there first.
   */
  private boolean conditionalPut(final SetId id, final Optional<Stored> stored, final long version,
                                 final Map<Key, Value> values) {
    final Map<String, AttributeValue> item = new HashMap<>(DynamodbConfigTable.key(id));
    item.put(VERSION, number(version));
    item.put(LIVE, AttributeValue.fromBool(true));
    final Map<String, AttributeValue> map = new HashMap<>();
    values.forEach((key, value) -> map.put(key.name(), AttributeValue.fromS(value.value())));
    item.put(VALUES, AttributeValue.fromM(map));
    try {
      if (stored.isPresent()) {
        dynamodb.putItem(request -> request
          .tableName(table)
          .item(item)
          .conditionExpression("#version = :version AND #live = :live")
          .expressionAttributeNames(Map.of("#version", VERSION, "#live", LIVE))
          .expressionAttributeValues(Map.of(
            ":version", number(stored.get().version()),
            ":live", AttributeValue.fromBool(stored.get().live()))));
      } else {
        dynamodb.putItem(request -> request
          .tableName(table)
          .item(item)
          .conditionExpression("attribute_not_exists(#pk)")
          .expressionAttributeNames(Map.of("#pk", PARTITION_KEY)));
      }
      return true;
    } catch (final ConditionalCheckFailedException e) {
      return false;
    }
  }

  /** Reads a set's item, strongly consistent; without its values unless asked. */
  private Optional<Stored> get(final SetId id, final boolean withValues) {
    final Map<String, String> names = withValues
      ? Map.of("#version", VERSION, "#live", LIVE, "#values", VALUES)
      : Map.of("#version", VERSION, "#live", LIVE);
    final Map<String, AttributeValue> item = dynamodb.getItem(request -> request
      .tableName(table)
      .key(DynamodbConfigTable.key(id))
      .consistentRead(true)
      .projectionExpression(String.join(", ", names.keySet()))
      .expressionAttributeNames(names)).item();
    if (item == null || item.isEmpty()) {
      return Optional.empty();
    }
    final long version = versionOf(item, id);
    final AttributeValue live = item.get(LIVE);
    if (live == null || live.bool() == null) {
      throw new ConfigDatastoreException("The item for " + id + " has no '" + LIVE + "' attribute");
    }
    final Map<Key, Value> values = new HashMap<>();
    if (live.bool() && withValues && item.containsKey(VALUES)) {
      item.get(VALUES).m().forEach((name, value) -> values.put(Key.from(name), new Value(value.s())));
    }
    return Optional.of(new Stored(version, live.bool(), values));
  }

  private static long versionOf(final Map<String, AttributeValue> item, final SetId id) {
    final AttributeValue attribute = item.get(VERSION);
    if (attribute == null || attribute.n() == null) {
      throw new ConfigDatastoreException("The item for " + id + " has no '" + VERSION + "' attribute");
    }
    try {
      final long version = Long.parseLong(attribute.n());
      if (version < 1) {
        throw new NumberFormatException("version below 1");
      }
      return version;
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("The item for " + id + " has an invalid version '" + attribute.n() + "'", e);
    }
  }

  /** Queries an application's live sets, optionally in one environment, and collects a part of each. */
  private <T> Set<T> collect(final Application application, final Optional<Environment> environment,
                             final Function<SortKey, T> part) {
    final Map<String, AttributeValue> values = new HashMap<>();
    values.put(":pk", AttributeValue.fromS(application.value()));
    values.put(":live", AttributeValue.fromBool(true));
    String condition = "#pk = :pk";
    if (environment.isPresent()) {
      condition += " AND begins_with(#sk, :prefix)";
      values.put(":prefix", AttributeValue.fromS(environment.get().value() + SEPARATOR));
    }
    final QueryRequest request = QueryRequest.builder()
      .tableName(table)
      .consistentRead(true)
      .keyConditionExpression(condition)
      .filterExpression("#live = :live")
      .projectionExpression("#sk")
      .expressionAttributeNames(Map.of("#pk", PARTITION_KEY, "#sk", SORT_KEY, "#live", LIVE))
      .expressionAttributeValues(values)
      .build();
    final Set<T> result = new HashSet<>();
    dynamodb.queryPaginator(request).items().forEach(item ->
      DynamodbConfigTable.parseSortKey(item.get(SORT_KEY).s()).map(part).ifPresent(result::add));
    return Set.copyOf(result);
  }

  private static AttributeValue number(final long value) {
    return AttributeValue.fromN(Long.toString(value));
  }

  /**
   * Pauses before a retry. The ceiling starts at 5ms and doubles each attempt up to
   * {@link #MAX_BACKOFF_MILLIS}; the pause is random below it, so racing writers spread out.
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

  static String errorCode(final DynamoDbException e) {
    return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
  }

  private <T> T call(final String action, final Supplier<T> call) {
    try {
      return call.get();
    } catch (final ResourceNotFoundException e) {
      throw new ConfigDatastoreException("Could not " + action + ": table '" + table + "' does not exist ("
        + e.getMessage() + ")", e);
    } catch (final DynamoDbException e) {
      if (ACCESS_DENIED.equals(errorCode(e))) {
        throw new ConfigDatastoreException("Could not " + action + ": access denied (" + e.getMessage()
          + "); run validate() to see which permission is missing", e);
      }
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    } catch (final SdkException e) {
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    }
  }

  /** A set's item as read; a deleted set has {@code live} false and no values. */
  private record Stored(long version, boolean live, Map<Key, Value> values) {
  }

  /** The checks behind {@link #validate(Access)}. */
  private record DynamodbValidation(DynamoDbClient dynamodb, String table) {

    ValidationResult run(final Access access, final String description) {
      final List<Check> checks = new ArrayList<>();
      final Check tableCheck = tableCheck();
      checks.add(tableCheck);
      if (tableCheck.status() == ValidationResult.Status.FAILED) {
        return new ValidationResult(description, access, checks); // nothing else can work
      }
      final Map<String, AttributeValue> probe = Map.of(
        PARTITION_KEY, AttributeValue.fromS(PROBE_PARTITION),
        SORT_KEY, AttributeValue.fromS(UUID.randomUUID().toString()));
      checks.add(check("dynamodb:GetItem", () -> {
        dynamodb.getItem(request -> request.tableName(table).key(probe).consistentRead(true));
        return "can read items";
      }, "readers need dynamodb:GetItem"));
      checks.add(check("dynamodb:Query", () -> {
        dynamodb.query(request -> request.tableName(table).limit(1)
          .keyConditionExpression("#pk = :pk")
          .expressionAttributeNames(Map.of("#pk", PARTITION_KEY))
          .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(PROBE_PARTITION))));
        return "can query an application's sets";
      }, "listing environments and set types needs dynamodb:Query"));
      checks.add(check("dynamodb:Scan", () -> {
        dynamodb.scan(request -> request.tableName(table).limit(1));
        return "can scan the table";
      }, "listing applications needs dynamodb:Scan"));
      if (access == Access.READ_WRITE) {
        writeChecks(checks, probe);
      }
      return new ValidationResult(description, access, checks);
    }

    private Check tableCheck() {
      try {
        final TableDescription description = dynamodb.describeTable(request -> request.tableName(table)).table();
        final List<KeySchemaElement> expected = List.of(
          KeySchemaElement.builder().attributeName(PARTITION_KEY).keyType(KeyType.HASH).build(),
          KeySchemaElement.builder().attributeName(SORT_KEY).keyType(KeyType.RANGE).build());
        if (!Set.copyOf(description.keySchema()).equals(Set.copyOf(expected))) {
          return Check.failed("table", "table '" + table + "' has key schema " + description.keySchema()
            + "; it needs partition key '" + PARTITION_KEY + "' and sort key '" + SORT_KEY
            + "', both strings (see DynamodbConfigTable)");
        }
        if (description.tableStatus() != TableStatus.ACTIVE && description.tableStatus() != TableStatus.UPDATING) {
          return Check.failed("table", "table '" + table + "' is " + description.tableStatus() + ", not ACTIVE");
        }
        return Check.passed("table", "table '" + table + "' exists, is " + description.tableStatus()
          + ", and has the expected key schema");
      } catch (final ResourceNotFoundException e) {
        return Check.failed("table", "table '" + table + "' does not exist; create it first "
          + "(DynamodbConfigTable.create, or the provider's create-table option)");
      } catch (final DynamoDbException e) {
        return Check.failed("table", "cannot describe table '" + table + "' (" + errorCode(e)
          + "); validation needs dynamodb:DescribeTable");
      } catch (final SdkException e) {
        return Check.failed("table", "could not reach DynamoDB: " + e.getMessage());
      }
    }

    private void writeChecks(final List<Check> checks, final Map<String, AttributeValue> probe) {
      final Map<String, AttributeValue> item = new HashMap<>(probe);
      item.put("probe", AttributeValue.fromN("1"));
      final Check put = check("dynamodb:PutItem", () -> {
        dynamodb.putItem(request -> request.tableName(table).item(item)
          .conditionExpression("attribute_not_exists(#pk)")
          .expressionAttributeNames(Map.of("#pk", PARTITION_KEY)));
        return "can write items";
      }, "writers need dynamodb:PutItem");
      checks.add(put);
      if (put.status() == ValidationResult.Status.FAILED) {
        return;
      }
      checks.add(check("dynamodb:UpdateItem", () -> {
        dynamodb.updateItem(request -> request.tableName(table).key(probe)
          .updateExpression("SET #probe = :probe")
          .conditionExpression("attribute_exists(#pk)")
          .expressionAttributeNames(Map.of("#probe", "probe", "#pk", PARTITION_KEY))
          .expressionAttributeValues(Map.of(":probe", AttributeValue.fromN("2"))));
        return "can update items";
      }, "deleting a set needs dynamodb:UpdateItem"));
      try {
        dynamodb.deleteItem(request -> request.tableName(table).key(probe));
        checks.add(Check.passed("probe cleanup", "removed the probe item"));
      } catch (final SdkException e) {
        checks.add(Check.warning("probe cleanup", "could not remove the probe item " + probe
          + " (the datastore never deletes items, so dynamodb:DeleteItem is optional): " + e.getMessage()));
      }
    }

    private static Check check(final String name, final Supplier<String> action, final String need) {
      try {
        return Check.passed(name, action.get());
      } catch (final DynamoDbException e) {
        return Check.failed(name, "failed (" + errorCode(e) + ": " + e.getMessage() + "); " + need);
      } catch (final SdkException e) {
        return Check.failed(name, "could not reach DynamoDB: " + e.getMessage());
      }
    }
  }
}
