package com.codeheadsystems.config.server.settings;

/**
 * A set's version as an entity tag ({@code "42"}), and {@code If-None-Match} matching
 * (RFC 9110, sections 8.8.3 and 13.1.2).
 */
final class EntityTags {

  private EntityTags() {
  }

  /**
   * Whether {@code If-None-Match} names the current version, so the answer is
   * {@code 304 Not Modified}. Accepts {@code *} (any version), a comma-separated list, and weak
   * tags ({@code W/"42"} matches version 42: RFC 9110 requires weak comparison here). A malformed
   * header never matches, so the client simply gets the full response.
   *
   * @param ifNoneMatch the header value, or null.
   * @param version     the set's current version.
   * @return true if the client already has this version.
   */
  static boolean matches(final String ifNoneMatch, final long version) {
    if (ifNoneMatch == null) {
      return false;
    }
    final String header = ifNoneMatch.trim();
    if (header.equals("*")) {
      return true;
    }
    final String wanted = Long.toString(version);
    boolean matched = false;
    boolean sawTag = false;
    int i = 0;
    while (true) {
      i = skipSpace(header, i);
      while (i < header.length() && header.charAt(i) == ',') {
        i = skipSpace(header, i + 1);
      }
      if (i >= header.length()) {
        return sawTag && matched;
      }
      if (header.startsWith("W/", i)) {
        i += 2;
      }
      if (i >= header.length() || header.charAt(i) != '"') {
        return false;
      }
      final int end = header.indexOf('"', i + 1);
      if (end < 0) {
        return false;
      }
      sawTag = true;
      matched |= header.substring(i + 1, end).equals(wanted);
      i = skipSpace(header, end + 1);
      if (i < header.length() && header.charAt(i) != ',') {
        return false;
      }
    }
  }

  private static int skipSpace(final String header, final int from) {
    int i = from;
    while (i < header.length() && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }
}
