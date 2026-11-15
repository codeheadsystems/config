package com.codeheadsystems.config.server.settings;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.auth.Caller;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Who may read an application's settings in an environment: the subjects listed in its METADATA
 * set, one key per trusted issuer.
 *
 * <pre>
 * auth.&lt;issuer name&gt;.subjects = &lt;subject&gt; &lt;subject&gt; ...
 * </pre>
 *
 * <p>Subjects are separated by whitespace. No list, or an empty one, means no access.
 */
final class AccessPolicy {

  private AccessPolicy() {
  }

  /**
   * The METADATA key listing an issuer's allowed subjects.
   *
   * @param issuer the issuer's configured name.
   * @return {@code auth.<issuer>.subjects}.
   */
  static Key subjectsKey(final String issuer) {
    return Key.from("auth." + issuer + ".subjects");
  }

  /**
   * Whether the caller is on the list.
   *
   * @param metadata the METADATA set's values; empty if there is no set.
   * @param caller   who is asking.
   * @return true if the caller may read the settings.
   */
  static boolean allows(final Map<Key, Value> metadata, final Caller caller) {
    return Optional.ofNullable(metadata.get(subjectsKey(caller.issuer())))
        .map(list -> Arrays.stream(list.value().trim().split("\\s+"))
            .anyMatch(subject -> !subject.isEmpty() && subject.equals(caller.subject())))
        .orElse(false);
  }
}
