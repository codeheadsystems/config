package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The object keys a datastore uses, all under one prefix:
 *
 * <pre>
 * {prefix}sets/{application}/{environment}/{settings|metadata}   one object per set
 * {prefix}_validate/{random}                                       probes written by validate()
 * </pre>
 *
 * <p>Application and environment names are validated by the model (lowercase letters, digits,
 * {@code -} and {@code _}), so they are always safe as key segments and need no encoding. Probe
 * keys start with {@code _}, which no application name can, and sit outside {@code sets/}.
 */
final class S3Keys {

  private static final String SETS = "sets/";
  private static final String PROBES = "_validate/";

  private final String prefix;

  S3Keys(final String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    this.prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
  }

  String prefix() {
    return prefix;
  }

  String set(final SetId id) {
    return environmentPrefix(id.application(), id.environment()) + typeName(id.type());
  }

  /** A fresh key for a validation probe; never a set. */
  String probe() {
    return prefix + PROBES + UUID.randomUUID();
  }

  /** The prefix every set is under. */
  String setsPrefix() {
    return prefix + SETS;
  }

  /** The prefix every set of an application is under. */
  String applicationPrefix(final Application application) {
    return setsPrefix() + application.value() + "/";
  }

  /** The prefix every set of an application in an environment is under. */
  String environmentPrefix(final Application application, final Environment environment) {
    return applicationPrefix(application) + environment.value() + "/";
  }

  /**
   * The set an object key belongs to.
   *
   * @param key an object key from a listing.
   * @return the set, or empty if the key is not one of ours (wrong place, invalid names, or an
   *         unknown type).
   */
  Optional<SetId> parse(final String key) {
    if (!key.startsWith(setsPrefix())) {
      return Optional.empty();
    }
    final String[] parts = key.substring(setsPrefix().length()).split("/", -1);
    if (parts.length != 3) {
      return Optional.empty();
    }
    final Optional<SetType> type = typeOf(parts[2]);
    if (type.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new SetId(Application.from(parts[0]), Environment.from(parts[1]), type.get()));
    } catch (final IllegalArgumentException e) {
      return Optional.empty(); // not a valid name, so not written by us
    }
  }

  private static Optional<SetType> typeOf(final String name) {
    for (final SetType type : SetType.values()) {
      if (typeName(type).equals(name)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  private static String typeName(final SetType type) {
    return type.name().toLowerCase(Locale.ROOT);
  }
}
