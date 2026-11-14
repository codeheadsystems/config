package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.SetId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL behind {@link SqlConfigDatastore}, as a JDBI SqlObject. Written for PostgreSQL
 * ({@code ON CONFLICT}, {@code FOR UPDATE}, {@code to_regclass}, {@code has_table_privilege}).
 *
 * <p>Every method that takes a {@link SetKey} binds its {@code :application}, {@code :environment}
 * and {@code :type}.
 */
@RegisterConstructorMapper(ConfigSetDao.SetRow.class)
interface ConfigSetDao {

  /** The tables the datastore uses, for validation. */
  String SET_TABLE = "config_set";

  /** The table holding each live set's keys and values. */
  String VALUE_TABLE = "config_value";

  /**
   * Locks a set's row for the rest of the transaction.
   *
   * @param set the set.
   * @return the row, or empty if the set has never been written.
   */
  @SqlQuery("SELECT version, live FROM config_set"
    + " WHERE application = :application AND environment = :environment AND set_type = :type"
    + " FOR UPDATE")
  Optional<SetRow> lock(@BindMethods SetKey set);

  /**
   * Creates a placeholder row (version 0, not live) for a set never written, so there is a row to
   * lock. Does nothing if the row exists; a concurrent insert of the same row waits for this
   * transaction to finish.
   *
   * @param set the set.
   */
  @SqlUpdate("INSERT INTO config_set (application, environment, set_type, version, live)"
    + " VALUES (:application, :environment, :type, 0, FALSE)"
    + " ON CONFLICT (application, environment, set_type) DO NOTHING")
  void insertPlaceholder(@BindMethods SetKey set);

  /**
   * Sets a set's version and state.
   *
   * @param set     the set.
   * @param version the new version.
   * @param live    false for a deleted set.
   */
  @SqlUpdate("UPDATE config_set SET version = :version, live = :live"
    + " WHERE application = :application AND environment = :environment AND set_type = :type")
  void updateSet(@BindMethods SetKey set, @Bind("version") long version, @Bind("live") boolean live);

  /**
   * A live set's version.
   *
   * @param set the set.
   * @return the version, or empty if the set does not exist.
   */
  @SqlQuery("SELECT version FROM config_set"
    + " WHERE application = :application AND environment = :environment AND set_type = :type AND live")
  Optional<Long> liveVersion(@BindMethods SetKey set);

  /**
   * A set's keys and values. Only live sets have any.
   *
   * @param set the set.
   * @return the values by key name.
   */
  @SqlQuery("SELECT name, value FROM config_value"
    + " WHERE application = :application AND environment = :environment AND set_type = :type")
  @KeyColumn("name")
  @ValueColumn("value")
  Map<String, String> values(@BindMethods SetKey set);

  /**
   * One value of a set.
   *
   * @param set  the set.
   * @param name the key name.
   * @return the value, or empty if the set or key does not exist.
   */
  @SqlQuery("SELECT value FROM config_value"
    + " WHERE application = :application AND environment = :environment AND set_type = :type AND name = :name")
  Optional<String> value(@BindMethods SetKey set, @Bind("name") String name);

  /**
   * Sets keys to values, adding or overwriting each. The two lists are parallel and not empty.
   *
   * @param set    the set.
   * @param names  the key names.
   * @param values the values.
   */
  @SqlBatch("INSERT INTO config_value (application, environment, set_type, name, value)"
    + " VALUES (:application, :environment, :type, :name, :value)"
    + " ON CONFLICT (application, environment, set_type, name) DO UPDATE SET value = EXCLUDED.value")
  void upsertValues(@BindMethods SetKey set, @Bind("name") List<String> names, @Bind("value") List<String> values);

  /**
   * Removes keys. The list is not empty.
   *
   * @param set   the set.
   * @param names the key names.
   */
  @SqlBatch("DELETE FROM config_value"
    + " WHERE application = :application AND environment = :environment AND set_type = :type AND name = :name")
  void deleteValues(@BindMethods SetKey set, @Bind("name") List<String> names);

  /**
   * Removes every key of a set.
   *
   * @param set the set.
   */
  @SqlUpdate("DELETE FROM config_value"
    + " WHERE application = :application AND environment = :environment AND set_type = :type")
  void deleteAllValues(@BindMethods SetKey set);

  /**
   * The applications with at least one live set.
   *
   * @return the application names.
   */
  @SqlQuery("SELECT DISTINCT application FROM config_set WHERE live")
  Set<String> applications();

  /**
   * The environments in which an application has at least one live set.
   *
   * @param application the application name.
   * @return the environment names.
   */
  @SqlQuery("SELECT DISTINCT environment FROM config_set WHERE application = :application AND live")
  Set<String> environments(@Bind("application") String application);

  /**
   * The types of live set for an application in an environment.
   *
   * @param application the application name.
   * @param environment the environment name.
   * @return the {@link com.codeheadsystems.config.datastore.SetType} names.
   */
  @SqlQuery("SELECT set_type FROM config_set"
    + " WHERE application = :application AND environment = :environment AND live")
  Set<String> types(@Bind("application") String application, @Bind("environment") String environment);

  /**
   * Whether a table is visible on the connection's search path.
   *
   * @param table the table name.
   * @return true if it exists.
   */
  @SqlQuery("SELECT to_regclass(CAST(:table AS text)) IS NOT NULL")
  boolean tableExists(@Bind("table") String table);

  /**
   * Whether the connection's user holds a privilege on a table, which must exist.
   *
   * @param table     the table name.
   * @param privilege one privilege, such as {@code INSERT}.
   * @return true if the privilege is held.
   */
  @SqlQuery("SELECT has_table_privilege(CAST(:table AS text), CAST(:privilege AS text))")
  boolean hasPrivilege(@Bind("table") String table, @Bind("privilege") String privilege);

  /**
   * A set's identifier as the strings stored in the tables.
   *
   * @param application the application name.
   * @param environment the environment name.
   * @param type        the {@link com.codeheadsystems.config.datastore.SetType} name.
   */
  record SetKey(String application, String environment, String type) {

    /**
     * The key for a set.
     *
     * @param id the set.
     * @return the key.
     */
    static SetKey of(final SetId id) {
      return new SetKey(id.application().value(), id.environment().value(), id.type().name());
    }
  }

  /**
   * A row of {@code config_set}.
   *
   * @param version the set's version; 0 for a placeholder never committed live.
   * @param live    false if the set was deleted, or is a placeholder.
   */
  record SetRow(long version, boolean live) {
  }
}
