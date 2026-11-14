package com.codeheadsystems.config.datastore.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastores;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DynamodbConfigDatastoreProviderTest {

  private static Configuration options(final Map<String, String> values) {
    return Configuration.from(key -> Optional.ofNullable(values.get(key.name())).map(Value::new));
  }

  /** Needs no DynamoDB. */
  @Nested
  class Options {

    @Test
    void isRegisteredUnderItsName() {
      assertThat(ConfigDatastores.available()).contains(DynamodbConfigDatastoreProvider.NAME);
      assertThat(new DynamodbConfigDatastoreProvider().name()).isEqualTo("dynamodb");
    }

    @Test
    void tableIsRequired() {
      assertThatThrownBy(() -> new DynamodbConfigDatastoreProvider().create(options(Map.of())))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("'table'");
    }

    @Test
    void badOptionsAreRejected() {
      assertThatThrownBy(() -> new DynamodbConfigDatastoreProvider().create(
        options(Map.of("table", "t", "max-set-bytes", "lots"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("max-set-bytes");
      assertThatThrownBy(() -> new DynamodbConfigDatastoreProvider().create(
        options(Map.of("table", "t", "create-table", "yes"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("create-table");
      assertThatThrownBy(() -> new DynamodbConfigDatastoreProvider().create(
        options(Map.of("table", "t", "access-key", "only-one"))))
        .isInstanceOf(ConfigDatastoreException.class).hasMessageContaining("secret-key");
    }
  }

  /** Needs DynamoDB Local. */
  @Nested
  class WithDynamodb {

    @BeforeAll
    static void requireDocker() {
      DynamodbLocal.requireDocker();
    }

    private Map<String, String> local(final String table) {
      return new HashMap<>(Map.of(
        "table", table,
        "endpoint", DynamodbLocal.endpoint().toString(),
        "region", DynamodbLocal.REGION,
        "access-key", DynamodbLocal.ACCESS_KEY,
        "secret-key", DynamodbLocal.SECRET_KEY));
    }

    @Test
    void aMissingTableFailsOnOpen() {
      assertThatThrownBy(() -> ConfigDatastores.open("dynamodb", options(local(DynamodbLocal.freshTableName()))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageStartingWith("Cannot open the dynamodb datastore on table");
    }

    @Test
    void opensByNameAndCreatesTheTableWhenAsked() {
      final Map<String, String> values = local(DynamodbLocal.freshTableName());
      values.put("create-table", "true");
      values.put("max-set-bytes", "2048");

      try (ConfigDatastore datastore = ConfigDatastores.open("dynamodb", options(values))) {
        assertThat(datastore).isInstanceOf(DynamodbConfigDatastore.class);
        assertThat(datastore.maxSetBytes()).isEqualTo(2048);
        datastore.validate(Access.READ_WRITE).requireOk();
      }
    }
  }
}
