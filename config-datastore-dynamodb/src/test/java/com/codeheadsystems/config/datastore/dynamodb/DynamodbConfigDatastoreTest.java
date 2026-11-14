package com.codeheadsystems.config.datastore.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetSize;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.ValidationResult.Check;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * What the contract does not cover: dependency injection, validation failures, the item layout and
 * the default size limit against DynamoDB's real item limit.
 */
class DynamodbConfigDatastoreTest {

  private static final SetId SETTINGS = SetId.settings(Application.from("billing"), Environment.from("prod"));
  private static final Key URL = Key.from("database.url");

  /** Needs no DynamoDB. */
  @Nested
  class Construction {

    @Test
    void isASingletonInjectedWithAClientAndANamedTable() {
      assertThat(DynamodbConfigDatastore.class.isAnnotationPresent(Singleton.class)).isTrue();
      final Constructor<?>[] injectable = Arrays.stream(DynamodbConfigDatastore.class.getConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
        .toArray(Constructor<?>[]::new);
      assertThat(injectable).hasSize(1);
      assertThat(injectable[0].getParameterTypes()).containsExactly(DynamoDbClient.class, String.class);
      final Named named = injectable[0].getParameters()[1].getAnnotation(Named.class);
      assertThat(named).isNotNull();
      assertThat(named.value()).isEqualTo(DynamodbConfigDatastore.TABLE);
    }

    @Test
    void rejectsNullsAndANonPositiveLimit() {
      try (DynamoDbClient client = DynamoDbClient.builder().region(software.amazon.awssdk.regions.Region.US_EAST_1).build()) {
        assertThatThrownBy(() -> new DynamodbConfigDatastore(null, "t")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamodbConfigDatastore(client, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DynamodbConfigDatastore(client, "t", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new DynamodbConfigDatastore(client, "t").maxSetBytes())
          .isEqualTo(DynamodbConfigDatastore.DEFAULT_MAX_SET_BYTES);
      }
    }
  }

  /** Needs DynamoDB Local. */
  @Nested
  class WithDynamodb {

    @BeforeAll
    static void requireDocker() {
      DynamodbLocal.requireDocker();
    }

    @Test
    void createTableIsIdempotent() {
      final String table = DynamodbLocal.freshTableName();

      assertThat(DynamodbConfigTable.create(DynamodbLocal.client(), table)).isTrue();
      assertThat(DynamodbConfigTable.create(DynamodbLocal.client(), table)).isFalse();
      new DynamodbConfigDatastore(DynamodbLocal.client(), table).validate(Access.READ_WRITE).requireOk();
    }

    @Test
    void aMissingTableFailsValidationAndOperations() {
      final DynamodbConfigDatastore datastore =
        new DynamodbConfigDatastore(DynamodbLocal.client(), DynamodbLocal.freshTableName());

      final ValidationResult result = datastore.validate(Access.READ);
      assertThat(result.failures()).extracting(Check::name).containsExactly("table");
      assertThat(result.datastore()).startsWith("dynamodb://us-east-1/test-");
      assertThatThrownBy(() -> datastore.load(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("does not exist");
    }

    @Test
    void aTableWithTheWrongKeySchemaFailsValidation() {
      final String table = DynamodbLocal.freshTableName();
      DynamodbLocal.client().createTable(request -> request
        .tableName(table)
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
        .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()));

      final ValidationResult result = new DynamodbConfigDatastore(DynamodbLocal.client(), table).validate(Access.READ);

      assertThat(result.failures()).extracting(Check::name).containsExactly("table");
      assertThat(result.failures().getFirst().detail()).contains("key schema");
    }

    @Test
    void aDeletedSetKeepsItsItemAndVersionButNotItsValues() {
      final String table = DynamodbLocal.freshTable();
      final DynamodbConfigDatastore datastore = new DynamodbConfigDatastore(DynamodbLocal.client(), table);
      final long version = datastore.replace(SETTINGS, Versions.NONE, Map.of(URL, new Value("jdbc:x")));

      datastore.delete(SETTINGS, version);

      final Map<String, AttributeValue> item = DynamodbLocal.client().getItem(request -> request
        .tableName(table).key(DynamodbConfigTable.key(SETTINGS)).consistentRead(true)).item();
      assertThat(item.get(DynamodbConfigTable.VERSION).n()).isEqualTo(Long.toString(version));
      assertThat(item.get(DynamodbConfigTable.LIVE).bool()).isFalse();
      assertThat(item).doesNotContainKey(DynamodbConfigTable.VALUES);
    }

    @Test
    void aLeftoverProbeItemDoesNotAppearInListings() {
      final String table = DynamodbLocal.freshTable();
      DynamodbLocal.client().putItem(request -> request.tableName(table).item(Map.of(
        DynamodbConfigTable.PARTITION_KEY, AttributeValue.fromS(DynamodbConfigDatastore.PROBE_PARTITION),
        DynamodbConfigTable.SORT_KEY, AttributeValue.fromS("leftover"),
        "probe", AttributeValue.fromN("1"))));

      final DynamodbConfigDatastore datastore = new DynamodbConfigDatastore(DynamodbLocal.client(), table);

      assertThat(datastore.applications()).isEmpty();
    }

    /**
     * The worst case for DynamoDB's per-entry overhead: as many short keys as possible, with empty
     * values, adding up to exactly the default limit. It must still fit in one 400 KB item.
     */
    @Test
    void aSetOfManyShortKeysAtTheDefaultLimitFitsInOneItem() {
      final DynamodbConfigDatastore datastore =
        new DynamodbConfigDatastore(DynamodbLocal.client(), DynamodbLocal.freshTable());
      final Map<Key, Value> values = shortKeysOfSize(DynamodbConfigDatastore.DEFAULT_MAX_SET_BYTES);

      final long version = datastore.replace(SETTINGS, Versions.NONE, values);

      assertThat(datastore.load(SETTINGS).orElseThrow().values()).hasSize(values.size());
      assertThat(datastore.version(SETTINGS)).isEqualTo(version);
    }

    /** Distinct keys, shortest first, with empty values; the last value pads to exactly {@code bytes}. */
    private static Map<Key, Value> shortKeysOfSize(final long bytes) {
      final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
      final Map<Key, Value> values = new HashMap<>();
      long size = 0;
      outer:
      for (int length = 1; length <= 3; length++) {
        final int[] digits = new int[length];
        while (true) {
          final StringBuilder name = new StringBuilder();
          for (final int digit : digits) {
            name.append(alphabet.charAt(digit));
          }
          if (size + length > bytes) {
            break outer;
          }
          values.put(Key.from(name.toString()), new Value(""));
          size += length;
          int position = length - 1;
          while (position >= 0 && ++digits[position] == alphabet.length()) {
            digits[position--] = 0;
          }
          if (position < 0) {
            break;
          }
        }
      }
      final Key last = values.keySet().iterator().next();
      values.put(last, new Value("x".repeat((int) (bytes - size))));
      assertThat(SetSize.bytes(values)).isEqualTo(bytes);
      return values;
    }
  }
}
