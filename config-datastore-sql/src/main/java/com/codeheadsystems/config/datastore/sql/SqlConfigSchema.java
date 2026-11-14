package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Creates and upgrades the tables {@link SqlConfigDatastore} uses, with Flyway.
 *
 * <p>The migrations live under {@value #LOCATION} and record what has run in their own history
 * table, {@value #HISTORY_TABLE}, so they can share a schema with an application's own Flyway
 * migrations. Tables are created in the connection's current schema (the first on its
 * {@code search_path}); to use another, set it on the connection, for example with pgjdbc's
 * {@code currentSchema} URL parameter.
 *
 * <p>Migrating needs DDL privileges, which the datastore itself does not: run it at deploy time,
 * or from a service with schema-owner credentials, and give readers and writers only the
 * privileges {@link SqlConfigDatastore#validate} checks for.
 */
public final class SqlConfigSchema {

  /** Where the migrations are on the classpath. */
  public static final String LOCATION = "classpath:com/codeheadsystems/config/datastore/sql/migration";

  /** The Flyway history table for these migrations. */
  public static final String HISTORY_TABLE = "config_schema_history";

  private SqlConfigSchema() {
  }

  /**
   * Brings the schema up to date.
   *
   * @param dataSource connections with DDL privileges on the target schema.
   * @return the number of migrations run; 0 if the schema was already current.
   * @throws ConfigDatastoreException if a migration fails.
   */
  public static int migrate(final DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    return migrate(configure().dataSource(dataSource));
  }

  /**
   * Brings the schema up to date.
   *
   * @param url      the JDBC URL.
   * @param user     the user, with DDL privileges on the target schema; may be null.
   * @param password the password; may be null.
   * @return the number of migrations run; 0 if the schema was already current.
   * @throws ConfigDatastoreException if a migration fails.
   */
  public static int migrate(final String url, final String user, final String password) {
    Objects.requireNonNull(url, "url must not be null");
    return migrate(configure().dataSource(url, user, password));
  }

  private static int migrate(final FluentConfiguration configuration) {
    try {
      return configuration.load().migrate().migrationsExecuted;
    } catch (final FlywayException e) {
      throw new ConfigDatastoreException("Could not migrate the config schema: " + e.getMessage(), e);
    }
  }

  private static FluentConfiguration configure() {
    return Flyway.configure(SqlConfigSchema.class.getClassLoader())
      .locations(LOCATION)
      .table(HISTORY_TABLE)
      // The schema may already hold an application's tables. Baseline below V1 so V1 still runs.
      .baselineOnMigrate(true)
      .baselineVersion("0");
  }
}
