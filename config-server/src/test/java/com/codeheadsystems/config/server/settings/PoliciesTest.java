package com.codeheadsystems.config.server.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.CacheConfig;
import com.codeheadsystems.config.server.auth.Caller;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoliciesTest {

  private static final Caller BILLING = new Caller("k8s", "system:serviceaccount:billing:billing");
  private static final Key SUBJECTS = Key.from("auth.k8s.subjects");
  private static final String WHERE = "application billing in environment prod";

  @Test
  void accessNeedsTheSubjectListedUnderItsIssuer() {
    final Map<Key, Value> metadata = Map.of(SUBJECTS,
        new Value("  system:serviceaccount:ops:ops\n system:serviceaccount:billing:billing  "));

    assertThat(AccessPolicy.allows(metadata, BILLING)).isTrue();
    assertThat(AccessPolicy.allows(metadata, new Caller("k8s", "system:serviceaccount:other:other"))).isFalse();
    assertThat(AccessPolicy.allows(metadata, new Caller("other-issuer", BILLING.subject()))).isFalse();
  }

  @Test
  void noListMeansNoAccess() {
    assertThat(AccessPolicy.allows(Map.of(), BILLING)).isFalse();
  }

  @Test
  void anEmptyListGrantsNobodyNotEvenAnEmptySubject() {
    for (final String list : new String[] {"", "   ", "\n\t"}) {
      final Map<Key, Value> metadata = Map.of(SUBJECTS, new Value(list));
      assertThat(AccessPolicy.allows(metadata, new Caller("k8s", ""))).as("'%s'", list).isFalse();
      assertThat(AccessPolicy.allows(metadata, BILLING)).as("'%s'", list).isFalse();
    }
  }

  @Test
  void aSubjectMustMatchExactly() {
    final Map<Key, Value> metadata = Map.of(SUBJECTS, new Value("system:serviceaccount:billing"));

    assertThat(AccessPolicy.allows(metadata, BILLING)).isFalse();
  }

  @Test
  void cacheControlUsesTheServerDefaults() {
    assertThat(CachePolicy.cacheControl(Map.of(), new CacheConfig(), WHERE))
        .isEqualTo("private, max-age=30, stale-while-revalidate=30, stale-if-error=86400");
  }

  @Test
  void cacheControlUsesMetadataWhenSet() {
    final Map<Key, Value> metadata = Map.of(
        CachePolicy.MAX_AGE, new Value("120"), CachePolicy.STALE_IF_ERROR, new Value(" 600 "));

    assertThat(CachePolicy.cacheControl(metadata, new CacheConfig(), WHERE))
        .isEqualTo("private, max-age=120, stale-while-revalidate=120, stale-if-error=600");
  }

  @Test
  void invalidMetadataFallsBackToTheDefaults() {
    final Map<Key, Value> metadata = Map.of(
        CachePolicy.MAX_AGE, new Value("soon"), CachePolicy.STALE_IF_ERROR, new Value("-5"));

    assertThat(CachePolicy.cacheControl(metadata, new CacheConfig(), WHERE))
        .isEqualTo("private, max-age=30, stale-while-revalidate=30, stale-if-error=86400");
  }

  @Test
  void hugeValuesAreCappedAtTwoToTheThirtyFirstMinusOne() {
    final Map<Key, Value> metadata = Map.of(
        CachePolicy.MAX_AGE, new Value(Long.toString(Long.MAX_VALUE)), CachePolicy.STALE_IF_ERROR,
        new Value("2147483648"));

    assertThat(CachePolicy.cacheControl(metadata, new CacheConfig(), WHERE)).isEqualTo(
        "private, max-age=2147483647, stale-while-revalidate=2147483647, stale-if-error=2147483647");
  }
}
