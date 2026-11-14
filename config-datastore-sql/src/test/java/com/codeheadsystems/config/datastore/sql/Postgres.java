package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.testkit.TestEnvironment;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container shared by every SQL test in the run, started on first use. Each test
 * gets its own schema, so it starts with an empty store. Testcontainers removes the container when
 * the JVM exits.
 */
final class Postgres {

  /** The newest PostgreSQL release. Keep in sync with the postgres service in docker-compose.yml. */
  static final String POSTGRES_IMAGE = "postgres:18.7";

  private static PostgreSQLContainer container;

  private Postgres() {
  }

  /** Skips the calling tests without Docker, or fails them on CI (see TestEnvironment). */
  static void requireDocker() {
    TestEnvironment.require(DockerClientFactory.instance().isDockerAvailable(), "Docker");
  }

  @SuppressWarnings("resource") // closed by Testcontainers at JVM exit
  static synchronized PostgreSQLContainer container() {
    if (container == null) {
      container = new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
        .withDatabaseName("config")
        .withUsername("config")
        .withPassword("config");
      container.start();
    }
    return container;
  }

  /** Connections as the container's superuser, to the database's default schema. */
  static Jdbi admin() {
    final PostgreSQLContainer postgres = container();
    return Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  /** Creates an empty schema no other test uses, and returns its name. */
  static String freshSchema() {
    final String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
    admin().useHandle(handle -> handle.execute("CREATE SCHEMA " + schema));
    return schema;
  }

  /** The JDBC URL with {@code schema} as the current schema. */
  static String url(final String schema) {
    final String url = container().getJdbcUrl();
    return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  /** Connections as the superuser with {@code schema} as the current schema. */
  static Jdbi jdbi(final String schema) {
    return Jdbi.create(url(schema), container().getUsername(), container().getPassword());
  }

  /** A fresh schema with the tables created, and connections to it. */
  static Jdbi migratedSchema() {
    final String schema = freshSchema();
    SqlConfigSchema.migrate(url(schema), container().getUsername(), container().getPassword());
    return jdbi(schema);
  }
}
