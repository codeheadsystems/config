package com.codeheadsystems.config.datastore.dynamodb;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastoreProvider;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Provides {@link DynamodbConfigDatastore} under the name {@value #NAME}.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code table}: the table; required. It is checked when the datastore is opened, so a
 *   wrong table or credentials fail immediately.</li>
 *   <li>{@code create-table}: {@code true} to create the table if it does not exist (see
 *   {@link DynamodbConfigTable#create}); defaults to {@code false}, since it needs
 *   {@code dynamodb:CreateTable}.</li>
 *   <li>{@code region}: the AWS region; defaults to {@code us-east-1}.</li>
 *   <li>{@code endpoint}: the service URL, for DynamoDB Local ({@code http://localhost:8000});
 *   defaults to Amazon DynamoDB.</li>
 *   <li>{@code access-key} and {@code secret-key}: static credentials; both or neither. Without
 *   them, the AWS default credentials chain is used.</li>
 *   <li>{@code max-set-bytes}: the size limit; defaults to
 *   {@link DynamodbConfigDatastore#DEFAULT_MAX_SET_BYTES}.</li>
 * </ul>
 *
 * <p>Opening checks the table exists ({@code dynamodb:DescribeTable}). Call
 * {@link ConfigDatastore#validate} to check the full set of permissions for how it will be used.
 */
public final class DynamodbConfigDatastoreProvider implements ConfigDatastoreProvider {

  /**
   * The backend's name.
   */
  public static final String NAME = "dynamodb";

  /**
   * Creates the provider. Called by {@link java.util.ServiceLoader}.
   */
  public DynamodbConfigDatastoreProvider() {
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ConfigDatastore create(final Configuration options) {
    final String table = option(options, "table")
      .orElseThrow(() -> new ConfigDatastoreException("The dynamodb datastore needs a 'table' option"));
    final long maxSetBytes = option(options, "max-set-bytes")
      .map(value -> parseLong("max-set-bytes", value))
      .orElse(DynamodbConfigDatastore.DEFAULT_MAX_SET_BYTES);
    final boolean createTable = option(options, "create-table")
      .map(value -> parseBoolean("create-table", value))
      .orElse(false);

    final DynamoDbClientBuilder builder = DynamoDbClient.builder()
      .region(Region.of(option(options, "region").orElse("us-east-1")));
    option(options, "endpoint").map(value -> parseUri("endpoint", value)).ifPresent(builder::endpointOverride);

    final Optional<String> accessKey = option(options, "access-key");
    final Optional<String> secretKey = option(options, "secret-key");
    if (accessKey.isPresent() != secretKey.isPresent()) {
      throw new ConfigDatastoreException("The dynamodb datastore needs both 'access-key' and 'secret-key', or neither");
    }
    accessKey.ifPresent(key -> builder.credentialsProvider(
      StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secretKey.get()))));

    final DynamoDbClient dynamodb = builder.build();
    try {
      if (createTable) {
        DynamodbConfigTable.create(dynamodb, table);
      }
      // Fail now on a wrong table or credentials, rather than on the first read.
      dynamodb.describeTable(request -> request.tableName(table));
    } catch (final SdkException | ConfigDatastoreException e) {
      dynamodb.close();
      throw new ConfigDatastoreException("Cannot open the dynamodb datastore on table '" + table + "': "
        + e.getMessage(), e);
    }
    return new DynamodbConfigDatastore(dynamodb, table, maxSetBytes, true);
  }

  private static Optional<String> option(final Configuration options, final String key) {
    return options.find(key).map(Value::value).map(String::trim);
  }

  private static long parseLong(final String name, final String value) {
    try {
      return Long.parseLong(value);
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("The dynamodb datastore's '" + name + "' must be a number: '" + value + "'", e);
    }
  }

  private static boolean parseBoolean(final String name, final String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new ConfigDatastoreException("The dynamodb datastore's '" + name
        + "' must be 'true' or 'false': '" + value + "'");
    };
  }

  private static URI parseUri(final String name, final String value) {
    try {
      return URI.create(value);
    } catch (final IllegalArgumentException e) {
      throw new ConfigDatastoreException("The dynamodb datastore's '" + name + "' is not a valid URL: '" + value + "'", e);
    }
  }
}
