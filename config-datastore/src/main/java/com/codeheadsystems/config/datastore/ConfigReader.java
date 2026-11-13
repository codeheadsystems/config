package com.codeheadsystems.config.datastore;

import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.util.Optional;
import java.util.Set;

/**
 * Reads sets from a datastore. config-library needs only this, so it can run with read-only
 * credentials.
 *
 * <p>A missing set is not an error: it loads as empty and has version {@link Versions#NONE}.
 */
public interface ConfigReader {

  /**
   * Loads a whole set.
   *
   * @param id the set.
   * @return the set, or empty if it does not exist.
   */
  Optional<Snapshot> load(SetId id);

  /**
   * Looks up one value. Backends that can read a single key cheaply may override this.
   *
   * @param id  the set.
   * @param key the key.
   * @return the value, or empty if the set or the key does not exist.
   */
  default Optional<Value> find(final SetId id, final Key key) {
    return load(id).flatMap(snapshot -> snapshot.find(key));
  }

  /**
   * The set's current version. Backends should override this with a query cheaper than a load,
   * since readers use it to check whether a set has changed.
   *
   * @param id the set.
   * @return the version, or {@link Versions#NONE} if the set does not exist.
   */
  default long version(final SetId id) {
    return load(id).map(Snapshot::version).orElse(Versions.NONE);
  }

  /**
   * Loads the {@link SetType#SETTINGS} set: the only set served to an application.
   *
   * @param application the application.
   * @param environment the environment.
   * @return the settings, or empty if there are none.
   */
  default Optional<Snapshot> settings(final Application application, final Environment environment) {
    return load(SetId.settings(application, environment));
  }

  /**
   * The applications that have at least one set.
   *
   * @return the applications.
   */
  Set<Application> applications();

  /**
   * The environments in which an application has at least one set.
   *
   * @param application the application.
   * @return the environments; empty if the application has none.
   */
  Set<Environment> environments(Application application);

  /**
   * The types of set that exist for an application in an environment.
   *
   * @param application the application.
   * @param environment the environment.
   * @return the types; empty if there are none.
   */
  Set<SetType> types(Application application, Environment environment);
}
