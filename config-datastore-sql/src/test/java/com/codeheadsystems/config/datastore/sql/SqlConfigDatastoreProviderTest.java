package com.codeheadsystems.config.datastore.sql;

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

class SqlConfigDatastoreProviderTest {

  private static Configuration options(final Map<String, String> values) {
    return Configuration.from(key -> Optional.ofNullable(values.get(key.name())).map(Value::new));
  }

  /** Needs no database. */
  @Nested
  class Options {

    @Test
    void isRegisteredUnderItsName() {
      assertThat(ConfigDatastores.available()).contains(SqlConfigDatastoreProvider.NAME);
      assertThat(new SqlConfigDatastoreProvider().name()).isEqualTo("sql");
    }

    @Test
    void urlIsRequired() {
      assertThatThrownBy(() -> new SqlConfigDatastoreProvider().create(options(Map.of())))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("'url'");
    }

    @Test
    void badNumbersAndBooleansAreRejected() {
      assertThatThrownBy(() -> new SqlConfigDatastoreProvider().create(
        options(Map.of("url", "jdbc:postgresql://unused/db", "max-set-bytes", "lots"))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("max-set-bytes");
      assertThatThrownBy(() -> new SqlConfigDatastoreProvider().create(
        options(Map.of("url", "jdbc:postgresql://unused/db", "migrate", "yes"))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("migrate");
    }

    @Test
    void anUnreachableDatabaseFailsOnOpenWithoutLeakingTheQueryString() {
      assertThatThrownBy(() -> new SqlConfigDatastoreProvider().create(
        options(Map.of("url", "jdbc:postgresql://127.0.0.1:1/none?password=hunter2"))))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageStartingWith("Cannot open the sql datastore at jdbc:postgresql://127.0.0.1:1/none:")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("?password"));
    }
  }

  /** Needs PostgreSQL. */
  @Nested
  class WithPostgres {

    @BeforeAll
    static void requireDocker() {
      Postgres.requireDocker();
    }

    @Test
    void opensByNameAndMigratesWhenAsked() {
      final Map<String, String> values = new HashMap<>(Map.of(
        "url", Postgres.url(Postgres.freshSchema()),
        "username", Postgres.container().getUsername(),
        "password", Postgres.container().getPassword(),
        "max-set-bytes", "2048"));

      try (ConfigDatastore withoutMigrate = ConfigDatastores.open("sql", options(values))) {
        assertThat(withoutMigrate.validate(Access.READ).ok()).isFalse();
      }
      values.put("migrate", "true");
      try (ConfigDatastore datastore = ConfigDatastores.open("sql", options(values))) {
        assertThat(datastore).isInstanceOf(SqlConfigDatastore.class);
        assertThat(datastore.maxSetBytes()).isEqualTo(2048);
        datastore.validate(Access.READ_WRITE).requireOk();
      }
    }
  }
}
