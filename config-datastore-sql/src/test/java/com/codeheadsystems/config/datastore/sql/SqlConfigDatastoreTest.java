package com.codeheadsystems.config.datastore.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.ValidationResult.Check;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the contract does not cover: the schema, validation failures, dependency injection, and the
 * storage layout.
 */
class SqlConfigDatastoreTest {

  private static final SetId SETTINGS = SetId.settings(Application.from("billing"), Environment.from("prod"));
  private static final Key URL = Key.from("database.url");

  /** Needs no database. */
  @Nested
  class Construction {

    @Test
    void isASingletonWithOneInjectableConstructorTakingJdbi() {
      assertThat(SqlConfigDatastore.class.isAnnotationPresent(Singleton.class)).isTrue();
      final Constructor<?>[] injectable = Arrays.stream(SqlConfigDatastore.class.getConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
        .toArray(Constructor<?>[]::new);
      assertThat(injectable).hasSize(1);
      assertThat(injectable[0].getParameterTypes()).containsExactly(Jdbi.class);
    }

    @Test
    void injectableConstructorUsesTheDefaultLimit() {
      assertThat(new SqlConfigDatastore(Jdbi.create("jdbc:postgresql://unused/db")).maxSetBytes())
        .isEqualTo(SqlConfigDatastore.DEFAULT_MAX_SET_BYTES);
    }

    @Test
    void rejectsANullJdbiAndANonPositiveLimit() {
      assertThatThrownBy(() -> new SqlConfigDatastore(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new SqlConfigDatastore(Jdbi.create("jdbc:postgresql://unused/db"), 0))
        .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnreachableDatabaseFailsWithADatastoreException() {
      final SqlConfigDatastore datastore = new SqlConfigDatastore(Jdbi.create("jdbc:postgresql://127.0.0.1:1/none"));

      assertThatThrownBy(() -> datastore.load(SETTINGS))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageStartingWith("Could not load");
      final ValidationResult result = datastore.validate(Access.READ);
      assertThat(result.ok()).isFalse();
      assertThat(result.failures()).extracting(Check::name).containsExactly("connection");
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
    void migratingTwiceRunsNothingTheSecondTime() {
      final String schema = Postgres.freshSchema();
      final PostgresLogin login = PostgresLogin.superuser(schema);

      assertThat(SqlConfigSchema.migrate(login.url(), login.user(), login.password())).isPositive();
      assertThat(SqlConfigSchema.migrate(login.url(), login.user(), login.password())).isZero();
    }

    @Test
    void migratingASchemaWithOtherTablesWorks() {
      final String schema = Postgres.freshSchema();
      Postgres.jdbi(schema).useHandle(handle -> handle.execute("CREATE TABLE application_table (id INT)"));
      final PostgresLogin login = PostgresLogin.superuser(schema);

      assertThat(SqlConfigSchema.migrate(login.url(), login.user(), login.password())).isPositive();
      assertThat(new SqlConfigDatastore(Postgres.jdbi(schema)).validate(Access.READ_WRITE).ok()).isTrue();
    }

    @Test
    void validationReportsMissingTables() {
      final SqlConfigDatastore datastore = new SqlConfigDatastore(Postgres.jdbi(Postgres.freshSchema()));

      final ValidationResult result = datastore.validate(Access.READ);

      assertThat(result.failures()).extracting(Check::name).containsExactly("schema");
      assertThat(result.datastore()).startsWith("jdbc:postgresql://").doesNotContain("?");
    }

    @Test
    void validationReportsMissingPrivileges() {
      final String schema = Postgres.freshSchema();
      PostgresLogin.superuser(schema).migrate();
      final PostgresLogin reader = PostgresLogin.role(schema, "SELECT");

      final SqlConfigDatastore datastore = new SqlConfigDatastore(reader.jdbi());

      assertThat(datastore.validate(Access.READ).ok()).isTrue();
      final ValidationResult readWrite = datastore.validate(Access.READ_WRITE);
      assertThat(readWrite.failures()).extracting(Check::name).containsExactly("write privileges");
      assertThat(readWrite.failures().getFirst().detail())
        .contains("INSERT on config_set", "DELETE on config_value");
    }

    @Test
    void aReaderWithOnlySelectCanReadButNotWrite() {
      final String schema = Postgres.freshSchema();
      final PostgresLogin owner = PostgresLogin.superuser(schema);
      owner.migrate();
      new SqlConfigDatastore(owner.jdbi()).put(SETTINGS, Versions.NONE, URL, new Value("jdbc:x"));
      final SqlConfigDatastore reader = new SqlConfigDatastore(PostgresLogin.role(schema, "SELECT").jdbi());

      assertThat(reader.find(SETTINGS, URL)).contains(new Value("jdbc:x"));
      assertThatThrownBy(() -> reader.put(SETTINGS, Versions.ANY, URL, new Value("jdbc:y")))
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("permission denied");
      assertThat(reader.find(SETTINGS, URL)).contains(new Value("jdbc:x"));
    }

    @Test
    void aDeletedSetKeepsItsRowButNotItsValues() {
      final Jdbi jdbi = Postgres.migratedSchema();
      final SqlConfigDatastore datastore = new SqlConfigDatastore(jdbi);
      final long version = datastore.replace(SETTINGS, Versions.NONE, Map.of(URL, new Value("jdbc:x")));

      datastore.delete(SETTINGS, version);

      final String row = jdbi.withHandle(handle -> handle.createQuery(
        "SELECT version || ':' || live FROM config_set").mapTo(String.class).one());
      final int values = jdbi.withHandle(handle -> handle.createQuery(
        "SELECT count(*) FROM config_value").mapTo(Integer.class).one());
      assertThat(row).isEqualTo(version + ":false");
      assertThat(values).isZero();
    }

    @Test
    void aWriteChangesOnlyTheRowsThatChanged() {
      final Jdbi jdbi = Postgres.migratedSchema();
      final SqlConfigDatastore datastore = new SqlConfigDatastore(jdbi);
      final Key kept = Key.from("kept");
      datastore.replace(SETTINGS, Versions.NONE, Map.of(URL, new Value("jdbc:x"), kept, new Value("same")));
      final long before = xmin(jdbi, kept);

      datastore.replace(SETTINGS, Versions.ANY, Map.of(URL, new Value("jdbc:y"), kept, new Value("same")));

      assertThat(xmin(jdbi, kept)).as("unchanged row was not rewritten").isEqualTo(before);
      assertThat(datastore.find(SETTINGS, URL)).contains(new Value("jdbc:y"));
    }

    /** The id of the transaction that last wrote a value's row. */
    private static long xmin(final Jdbi jdbi, final Key key) {
      return jdbi.withHandle(handle -> handle.createQuery(
          "SELECT CAST(CAST(xmin AS text) AS bigint) FROM config_value WHERE name = :name")
        .bind("name", key.name()).mapTo(Long.class).one());
    }
  }

  /** A login to the test database with a schema as its current schema. */
  record PostgresLogin(String url, String user, String password) {

    static PostgresLogin superuser(final String schema) {
      return new PostgresLogin(Postgres.url(schema), Postgres.container().getUsername(),
        Postgres.container().getPassword());
    }

    /** A new role holding only the given privileges on every table in the schema. */
    static PostgresLogin role(final String schema, final String privileges) {
      final String role = "role_" + UUID.randomUUID().toString().replace("-", "");
      Postgres.admin().useHandle(handle -> {
        handle.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'secret'");
        handle.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        handle.execute("GRANT " + privileges + " ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
      });
      return new PostgresLogin(Postgres.url(schema), role, "secret");
    }

    Jdbi jdbi() {
      return Jdbi.create(url, user, password);
    }

    void migrate() {
      SqlConfigSchema.migrate(url, user, password);
    }
  }
}
