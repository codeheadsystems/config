package com.codeheadsystems.config.datastore.sql;

import com.codeheadsystems.config.datastore.Access;
import com.codeheadsystems.config.datastore.Change;
import com.codeheadsystems.config.datastore.Changes;
import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetSize;
import com.codeheadsystems.config.datastore.SetTooLargeException;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.ValidationResult;
import com.codeheadsystems.config.datastore.ValidationResult.Check;
import com.codeheadsystems.config.datastore.VersionConflictException;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.datastore.sql.ConfigSetDao.SetKey;
import com.codeheadsystems.config.datastore.sql.ConfigSetDao.SetRow;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * A {@link ConfigDatastore} in a SQL database, written for PostgreSQL. Each set is a row of
 * {@code config_set} holding its version, and its keys and values are rows of
 * {@code config_value}. Create the tables with {@link SqlConfigSchema} first.
 *
 * <p>A deleted set keeps its {@code config_set} row, marked not live, so its version is never
 * reused: a set created again continues from it.
 *
 * <p>Every write is one {@code READ COMMITTED} transaction that first locks the set's row with
 * {@code SELECT ... FOR UPDATE}, creating a placeholder row ({@code INSERT ... ON CONFLICT DO
 * NOTHING}) if the set has never been written. Writers to one set therefore run one at a time,
 * so a write never loses a race and never needs a retry, and a failed write rolls back entirely.
 * Reads of a whole set run in one {@code REPEATABLE READ} transaction, so a snapshot's values
 * and version always match.
 *
 * <p>The datastore does not own the {@link Jdbi}: {@link #close()} does nothing, and the caller
 * closes whatever connection pool backs it. The constructor installs JDBI's
 * {@link SqlObjectPlugin} on the given {@code Jdbi}, which is harmless if it is already installed.
 *
 * <p>For dependency injection, the {@linkplain Inject injectable} constructor needs only a
 * {@code Jdbi}, and the class is a {@link Singleton}. Use {@link #SqlConfigDatastore(Jdbi, long)}
 * from a provider method to set a size limit other than {@link #DEFAULT_MAX_SET_BYTES}.
 */
@Singleton
public final class SqlConfigDatastore implements ConfigDatastore {

  /**
   * The size limit used when none is given: 1 MiB. PostgreSQL allows far larger values, but every
   * read moves the whole set.
   */
  public static final long DEFAULT_MAX_SET_BYTES = 1024L * 1024L;

  /** Privileges a writer needs, by table; readers need only {@code SELECT}. */
  private static final Map<String, List<String>> WRITE_PRIVILEGES = Map.of(
    ConfigSetDao.SET_TABLE, List.of("INSERT", "UPDATE"),
    ConfigSetDao.VALUE_TABLE, List.of("INSERT", "UPDATE", "DELETE"));

  private final Jdbi jdbi;
  private final long maxSetBytes;

  /**
   * Creates a datastore with the default size limit, {@link #DEFAULT_MAX_SET_BYTES}.
   *
   * @param jdbi connections to the database holding the tables.
   */
  @Inject
  public SqlConfigDatastore(final Jdbi jdbi) {
    this(jdbi, DEFAULT_MAX_SET_BYTES);
  }

  /**
   * Creates a datastore.
   *
   * @param jdbi        connections to the database holding the tables.
   * @param maxSetBytes the largest set this datastore stores, at least 1.
   */
  public SqlConfigDatastore(final Jdbi jdbi, final long maxSetBytes) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    if (maxSetBytes < 1) {
      throw new IllegalArgumentException("maxSetBytes must be at least 1: " + maxSetBytes);
    }
    this.maxSetBytes = maxSetBytes;
    jdbi.installPlugin(new SqlObjectPlugin());
  }

  // Reading

  @Override
  public Optional<Snapshot> load(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    final SetKey key = SetKey.of(id);
    return sqlCall("load " + id, () -> jdbi.inTransaction(TransactionIsolationLevel.REPEATABLE_READ, handle -> {
      final ConfigSetDao dao = handle.attach(ConfigSetDao.class);
      return dao.liveVersion(key).map(version -> new Snapshot(id, values(dao, key), version));
    }));
  }

  @Override
  public Optional<Value> find(final SetId id, final Key key) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(key, "key must not be null");
    // Only live sets have values, so this needs no check of the set's row.
    return sqlCall("find " + key.name() + " in " + id, () -> jdbi.withExtension(ConfigSetDao.class,
      dao -> dao.value(SetKey.of(id), key.name()).map(Value::new)));
  }

  @Override
  public long version(final SetId id) {
    Objects.requireNonNull(id, "id must not be null");
    return sqlCall("read the version of " + id, () -> jdbi.withExtension(ConfigSetDao.class,
      dao -> dao.liveVersion(SetKey.of(id)).orElse(Versions.NONE)));
  }

  @Override
  public Set<Application> applications() {
    return sqlCall("list applications", () -> jdbi.withExtension(ConfigSetDao.class,
      dao -> convert(dao.applications(), Application::from)));
  }

  @Override
  public Set<Environment> environments(final Application application) {
    Objects.requireNonNull(application, "application must not be null");
    return sqlCall("list environments of " + application.value(), () -> jdbi.withExtension(ConfigSetDao.class,
      dao -> convert(dao.environments(application.value()), Environment::from)));
  }

  @Override
  public Set<SetType> types(final Application application, final Environment environment) {
    Objects.requireNonNull(application, "application must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    return sqlCall("list set types of " + application.value() + "/" + environment.value(),
      () -> jdbi.withExtension(ConfigSetDao.class, dao -> {
        final Set<SetType> result = EnumSet.noneOf(SetType.class);
        dao.types(application.value(), environment.value()).forEach(type -> result.add(SetType.valueOf(type)));
        return Set.copyOf(result);
      }));
  }

  // Writing

  @Override
  public long apply(final SetId id, final long expectedVersion, final List<Change> changes) {
    Objects.requireNonNull(id, "id must not be null");
    final List<Change> validated = Changes.validate(changes);
    Versions.validateExpected(expectedVersion);
    final SetKey key = SetKey.of(id);
    return sqlCall("write " + id, () -> jdbi.inTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
      final ConfigSetDao dao = handle.attach(ConfigSetDao.class);
      final SetRow row = lockOrCreate(dao, key, id);
      final long current = row.live() ? row.version() : Versions.NONE;
      if (!Versions.matches(expectedVersion, current)) {
        throw new VersionConflictException(id, expectedVersion, current);
      }
      final Map<Key, Value> before = row.live() ? values(dao, key) : Map.of();
      final Map<Key, Value> after = Changes.apply(before, validated);
      final long bytes = SetSize.bytes(after);
      if (bytes > maxSetBytes) {
        throw new SetTooLargeException(id, bytes, maxSetBytes);
      }
      writeDifference(dao, key, before, after);
      // Continue from whatever the row last held: the live version, or a deleted set's.
      final long next = row.version() + 1;
      dao.updateSet(key, next, true);
      return next;
    }));
  }

  @Override
  public boolean delete(final SetId id, final long expectedVersion) {
    Objects.requireNonNull(id, "id must not be null");
    Versions.validateExpected(expectedVersion);
    final SetKey key = SetKey.of(id);
    return sqlCall("delete " + id, () -> jdbi.inTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
      final ConfigSetDao dao = handle.attach(ConfigSetDao.class);
      final long current = dao.lock(key).filter(SetRow::live).map(SetRow::version).orElse(Versions.NONE);
      if (!Versions.matches(expectedVersion, current)) {
        throw new VersionConflictException(id, expectedVersion, current);
      }
      if (current == Versions.NONE) {
        return false;
      }
      dao.deleteAllValues(key);
      dao.updateSet(key, current, false); // keeps the version, so a set created again continues past it
      return true;
    }));
  }

  @Override
  public long maxSetBytes() {
    return maxSetBytes;
  }

  // Validation

  /**
   * {@inheritDoc}
   *
   * <p>Checks that the database is reachable, that the tables exist, and that the user holds the
   * privileges the access needs: {@code SELECT} on both tables to read, and to write also
   * {@code INSERT} and {@code UPDATE} on {@code config_set} and {@code INSERT}, {@code UPDATE} and
   * {@code DELETE} on {@code config_value}. Writes nothing.
   */
  @Override
  public ValidationResult validate(final Access access) {
    Objects.requireNonNull(access, "access must not be null");
    final List<Check> checks = new ArrayList<>();
    final String[] description = {"sql"};
    try {
      jdbi.useHandle(handle -> {
        final DatabaseMetaData metadata = handle.getConnection().getMetaData();
        description[0] = withoutQuery(metadata.getURL());
        checks.add(Check.passed("connection", "connected to " + metadata.getDatabaseProductName() + " "
          + metadata.getDatabaseProductVersion() + " as " + metadata.getUserName()));
        final ConfigSetDao dao = handle.attach(ConfigSetDao.class);
        final List<String> missing = tables().stream().filter(table -> !dao.tableExists(table)).toList();
        if (!missing.isEmpty()) {
          checks.add(Check.failed("schema", "missing tables " + missing
            + "; create them with SqlConfigSchema.migrate (or the provider's migrate option)"));
          return;
        }
        checks.add(Check.passed("schema", "tables " + tables() + " exist"));
        checks.add(privileges(dao, "SELECT privilege", tables().stream()
          .collect(Collectors.toMap(table -> table, table -> List.of("SELECT"))), "readers"));
        if (access == Access.READ_WRITE) {
          checks.add(privileges(dao, "write privileges", WRITE_PRIVILEGES, "writers"));
        }
      });
    } catch (final JdbiException | SQLException e) {
      checks.add(Check.failed("connection", "cannot use the database: " + e.getMessage()));
    }
    return new ValidationResult(description[0], access, checks);
  }

  // Helpers

  /** Locks the set's row, first creating a placeholder if the set has never been written. */
  private static SetRow lockOrCreate(final ConfigSetDao dao, final SetKey key, final SetId id) {
    final Optional<SetRow> existing = dao.lock(key);
    if (existing.isPresent()) {
      return existing.get();
    }
    dao.insertPlaceholder(key);
    return dao.lock(key).orElseThrow(() ->
      new ConfigDatastoreException("The row for " + id + " disappeared while writing it"));
  }

  /** Writes only what changed: removed keys are deleted, and new or changed keys upserted. */
  private static void writeDifference(final ConfigSetDao dao, final SetKey key,
                                      final Map<Key, Value> before, final Map<Key, Value> after) {
    final List<String> removed = before.keySet().stream()
      .filter(name -> !after.containsKey(name))
      .map(Key::name)
      .toList();
    if (!removed.isEmpty()) {
      dao.deleteValues(key, removed);
    }
    final List<String> names = new ArrayList<>();
    final List<String> values = new ArrayList<>();
    after.forEach((name, value) -> {
      if (!value.equals(before.get(name))) {
        names.add(name.name());
        values.add(value.value());
      }
    });
    if (!names.isEmpty()) {
      dao.upsertValues(key, names, values);
    }
  }

  private static Map<Key, Value> values(final ConfigSetDao dao, final SetKey key) {
    final Map<Key, Value> result = new HashMap<>();
    dao.values(key).forEach((name, value) -> result.put(Key.from(name), new Value(value)));
    return result;
  }

  private static <T> Set<T> convert(final Set<String> names, final Function<String, T> from) {
    return names.stream().map(from).collect(Collectors.toUnmodifiableSet());
  }

  private static List<String> tables() {
    return List.of(ConfigSetDao.SET_TABLE, ConfigSetDao.VALUE_TABLE);
  }

  private static Check privileges(final ConfigSetDao dao, final String name,
                                  final Map<String, List<String>> required, final String who) {
    final List<String> missing = new ArrayList<>();
    required.forEach((table, privileges) -> privileges.stream()
      .filter(privilege -> !dao.hasPrivilege(table, privilege))
      .forEach(privilege -> missing.add(privilege + " on " + table)));
    if (missing.isEmpty()) {
      return Check.passed(name, "holds " + required);
    }
    missing.sort(null);
    return Check.failed(name, "missing " + String.join(", ", missing) + "; " + who + " need " + required);
  }

  /** A JDBC URL without its query string, which may carry a password. */
  private static String withoutQuery(final String url) {
    if (url == null) {
      return "sql";
    }
    final int query = url.indexOf('?');
    return query < 0 ? url : url.substring(0, query);
  }

  private static <T> T sqlCall(final String action, final Supplier<T> call) {
    try {
      return call.get();
    } catch (final JdbiException e) {
      throw new ConfigDatastoreException("Could not " + action + ": " + e.getMessage(), e);
    }
  }
}
