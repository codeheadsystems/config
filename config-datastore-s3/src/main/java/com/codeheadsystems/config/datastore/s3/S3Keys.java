package com.codeheadsystems.config.datastore.s3;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The object keys a datastore uses, all under one prefix:
 *
 * <pre>
 * {prefix}sets/_{application}/_{environment}/{type}        a live set
 * {prefix}tombstones/_{application}/_{environment}/{type}  the last version of a deleted set
 * </pre>
 *
 * <p>Application and environment names are URL-encoded, so a {@code /} in a name cannot create a
 * new level, and prefixed with {@code _}, so an empty name is still a segment.
 */
final class S3Keys {

  private static final String SETS = "sets/";
  private static final String TOMBSTONES = "tombstones/";
  private static final String SEGMENT_MARKER = "_";

  private final String prefix;

  S3Keys(final String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    this.prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
  }

  String prefix() {
    return prefix;
  }

  String live(final SetId id) {
    return environmentPrefix(id.application(), id.environment()) + typeName(id.type());
  }

  String tombstone(final SetId id) {
    return prefix + TOMBSTONES + segment(id.application().value()) + "/" + segment(id.environment().value())
      + "/" + typeName(id.type());
  }

  /** Where the live sets' applications are listed: one common prefix per application. */
  String applicationsPrefix() {
    return prefix + SETS;
  }

  /** Where an application's environments are listed: one common prefix per environment. */
  String applicationPrefix(final Application application) {
    return applicationsPrefix() + segment(application.value()) + "/";
  }

  /** Where an environment's sets are listed: one object per set type. */
  String environmentPrefix(final Application application, final Environment environment) {
    return applicationPrefix(application) + segment(environment.value()) + "/";
  }

  /**
   * Decodes the name in a common prefix such as {@code config/sets/_billing/}.
   *
   * @param parent       the prefix that was listed.
   * @param commonPrefix one common prefix returned by the listing.
   * @return the decoded name, or empty if the prefix is not one of ours.
   */
  Optional<String> nameIn(final String parent, final String commonPrefix) {
    if (!commonPrefix.startsWith(parent) || !commonPrefix.endsWith("/")) {
      return Optional.empty();
    }
    return decode(commonPrefix.substring(parent.length(), commonPrefix.length() - 1));
  }

  /**
   * The set type of an object listed under an environment prefix.
   *
   * @param parent the environment prefix that was listed.
   * @param key    one object key returned by the listing.
   * @return the type, or empty if the key is not a set.
   */
  Optional<SetType> typeIn(final String parent, final String key) {
    if (!key.startsWith(parent)) {
      return Optional.empty();
    }
    final String name = key.substring(parent.length());
    for (final SetType type : SetType.values()) {
      if (typeName(type).equals(name)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  static String segment(final String name) {
    return SEGMENT_MARKER + URLEncoder.encode(name, StandardCharsets.UTF_8);
  }

  static Optional<String> decode(final String segment) {
    if (!segment.startsWith(SEGMENT_MARKER) || segment.contains("/")) {
      return Optional.empty();
    }
    try {
      return Optional.of(URLDecoder.decode(segment.substring(SEGMENT_MARKER.length()), StandardCharsets.UTF_8));
    } catch (final IllegalArgumentException e) {
      return Optional.empty(); // not written by us
    }
  }

  private static String typeName(final SetType type) {
    return type.name().toLowerCase(Locale.ROOT);
  }
}
