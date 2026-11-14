package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.ConfigDatastoreProvider;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.Value;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;

/**
 * Provides {@link SqlConfigDatastore} under the name {@value #NAME}, for applications that open
 * datastores by name through {@link com.codeheadsystems.config.datastore.ConfigDatastores}.
 *
 * <p>Options:
 * <ul>
 *   <li>{@code url}: the JDBC URL, such as {@code jdbc:postgresql://localhost:5432/config};
 *   required. Use pgjdbc's {@code currentSchema} parameter to keep the tables in a schema other
 *   than the user's default.</li>
 *   <li>{@code username} and {@code password}: the credentials; optional, as they may be in the
 *   URL.</li>
 *   <li>{@code migrate}: {@code true} to create or upgrade the tables when the datastore is opened
 *   (see {@link SqlConfigSchema}); defaults to {@code false}, since it needs DDL privileges.</li>
 *   <li>{@code max-set-bytes}: the size limit; defaults to
 *   {@link SqlConfigDatastore#DEFAULT_MAX_SET_BYTES}.</li>
 * </ul>
 *
 * <p>Connections come from {@link java.sql.DriverManager}, one per operation, without pooling.
 * That suits tools and light use; for a service, build a {@link Jdbi} over a pooled
 * {@link javax.sql.DataSource} and construct (or inject) {@link SqlConfigDatastore} directly.
 *
 * <p>Opening checks the database is reachable. Call {@link ConfigDatastore#validate} to check the
 * tables and privileges for how it will be used.
 */
public final class SqlConfigDatastoreProvider implements ConfigDatastoreProvider {

  /**
   * The backend's name.
   */
  public static final String NAME = "sql";

  /**
   * Creates the provider. Called by {@link java.util.ServiceLoader}.
   */
  public SqlConfigDatastoreProvider() {
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public ConfigDatastore create(final Configuration options) {
    final String url = option(options, "url")
      .orElseThrow(() -> new ConfigDatastoreException("The sql datastore needs a 'url' option"));
    final String username = option(options, "username").orElse(null);
    final String password = option(options, "password").orElse(null);
    final long maxSetBytes = option(options, "max-set-bytes")
      .map(value -> parseLong("max-set-bytes", value))
      .orElse(SqlConfigDatastore.DEFAULT_MAX_SET_BYTES);
    final boolean migrate = option(options, "migrate").map(value -> parseBoolean("migrate", value)).orElse(false);

    if (migrate) {
      SqlConfigSchema.migrate(url, username, password);
    }
    final Jdbi jdbi = username == null && password == null
      ? Jdbi.create(url)
      : Jdbi.create(url, username, password);
    try {
      // Fail now on a wrong URL or credentials, rather than on the first read.
      jdbi.useHandle(handle -> handle.execute("SELECT 1"));
    } catch (final JdbiException e) {
      throw new ConfigDatastoreException("Cannot open the sql datastore at " + withoutQuery(url) + ": "
        + e.getMessage(), e);
    }
    return new SqlConfigDatastore(jdbi, maxSetBytes);
  }

  private static Optional<String> option(final Configuration options, final String key) {
    return options.find(key).map(Value::value).map(String::trim);
  }

  private static String withoutQuery(final String url) {
    final int query = url.indexOf('?');
    return query < 0 ? url : url.substring(0, query);
  }

  private static long parseLong(final String name, final String value) {
    try {
      return Long.parseLong(value);
    } catch (final NumberFormatException e) {
      throw new ConfigDatastoreException("The sql datastore's '" + name + "' must be a number: '" + value + "'", e);
    }
  }

  private static boolean parseBoolean(final String name, final String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new ConfigDatastoreException("The sql datastore's '" + name
        + "' must be 'true' or 'false': '" + value + "'");
    };
  }
}
