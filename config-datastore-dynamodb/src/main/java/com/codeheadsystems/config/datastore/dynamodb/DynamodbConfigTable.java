package com.codeheadsystems.config.datastore.dynamodb;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * The table {@link DynamodbConfigDatastore} uses, and how a set maps to an item in it.
 *
 * <p>The table's key is a string partition key {@value #PARTITION_KEY}, holding the application,
 * and a string sort key {@value #SORT_KEY}, holding the environment and the set type as
 * {@code environment#TYPE}. An application's sets therefore share a partition, so listing its
 * environments or an environment's set types is a query; listing applications is a scan.
 *
 * <p>Each set item also has {@value #VERSION} (a number), {@value #LIVE} (a boolean, false once the
 * set is deleted) and, while live, {@value #VALUES} (a map from key name to string value).
 */
public final class DynamodbConfigTable {

  /** The partition key attribute: the application. */
  public static final String PARTITION_KEY = "pk";

  /** The sort key attribute: {@code environment#TYPE}. */
  public static final String SORT_KEY = "sk";

  /** The set's version. */
  public static final String VERSION = "version";

  /** Whether the set exists; false for a deleted set, whose item keeps its version. */
  public static final String LIVE = "live";

  /** The set's keys and values, as a map. A reserved word in DynamoDB, so always aliased. */
  public static final String VALUES = "values";

  /** Separates the environment from the set type in the sort key; environments cannot contain it. */
  static final String SEPARATOR = "#";

  private DynamodbConfigTable() {
  }

  /**
   * Creates the table, with on-demand billing, and waits until it is active. Does nothing if it
   * already exists. Needs {@code dynamodb:CreateTable} and {@code dynamodb:DescribeTable}, which
   * the datastore itself does not: run it at deploy time, or create the table with your
   * infrastructure tooling using the key schema described above.
   *
   * @param dynamodb the client.
   * @param table    the table name.
   * @return true if the table was created; false if it already existed.
   * @throws ConfigDatastoreException if the table cannot be created.
   */
  public static boolean create(final DynamoDbClient dynamodb, final String table) {
    Objects.requireNonNull(dynamodb, "dynamodb must not be null");
    Objects.requireNonNull(table, "table must not be null");
    try {
      dynamodb.createTable(request -> request
        .tableName(table)
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .attributeDefinitions(
          AttributeDefinition.builder().attributeName(PARTITION_KEY).attributeType(ScalarAttributeType.S).build(),
          AttributeDefinition.builder().attributeName(SORT_KEY).attributeType(ScalarAttributeType.S).build())
        .keySchema(
          KeySchemaElement.builder().attributeName(PARTITION_KEY).keyType(KeyType.HASH).build(),
          KeySchemaElement.builder().attributeName(SORT_KEY).keyType(KeyType.RANGE).build()));
      dynamodb.waiter().waitUntilTableExists(request -> request.tableName(table));
      return true;
    } catch (final ResourceInUseException e) {
      return false; // already exists
    } catch (final SdkException e) {
      throw new ConfigDatastoreException("Could not create DynamoDB table '" + table + "': " + e.getMessage(), e);
    }
  }

  /** The key of a set's item. */
  static Map<String, AttributeValue> key(final SetId id) {
    return Map.of(
      PARTITION_KEY, AttributeValue.fromS(id.application().value()),
      SORT_KEY, AttributeValue.fromS(sortKey(id)));
  }

  static String sortKey(final SetId id) {
    return id.environment().value() + SEPARATOR + id.type().name();
  }

  /** The environment and set type in a sort key, or empty if it is not one this datastore wrote. */
  static Optional<SortKey> parseSortKey(final String sortKey) {
    final int separator = sortKey.indexOf(SEPARATOR);
    if (separator < 1) {
      return Optional.empty();
    }
    try {
      return Optional.of(new SortKey(sortKey.substring(0, separator),
        SetType.valueOf(sortKey.substring(separator + 1))));
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** A parsed sort key. */
  record SortKey(String environment, SetType type) {
  }
}
