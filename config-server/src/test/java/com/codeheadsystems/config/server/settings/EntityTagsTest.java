package com.codeheadsystems.config.server.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntityTagsTest {

  @Test
  void matchesTheVersion() {
    assertThat(EntityTags.matches("\"42\"", 42)).isTrue();
    assertThat(EntityTags.matches("\"41\"", 42)).isFalse();
  }

  @Test
  void usesWeakComparison() {
    assertThat(EntityTags.matches("W/\"42\"", 42)).isTrue();
  }

  @Test
  void matchesAnyTagInAList() {
    assertThat(EntityTags.matches("\"40\", W/\"41\",\"42\"", 42)).isTrue();
    assertThat(EntityTags.matches("\"42\" , \"43\"", 42)).isTrue();
    assertThat(EntityTags.matches("\"40\", \"41\"", 42)).isFalse();
  }

  @Test
  void toleratesEmptyListElements() {
    assertThat(EntityTags.matches(", \"42\",,", 42)).isTrue();
  }

  @Test
  void starMatchesAnyVersion() {
    assertThat(EntityTags.matches(" * ", 7)).isTrue();
  }

  @Test
  void absentOrMalformedNeverMatches() {
    assertThat(EntityTags.matches(null, 42)).isFalse();
    assertThat(EntityTags.matches("", 42)).isFalse();
    assertThat(EntityTags.matches(" , ", 42)).isFalse();
    assertThat(EntityTags.matches("42", 42)).isFalse();
    assertThat(EntityTags.matches("\"42", 42)).isFalse();
    assertThat(EntityTags.matches("\"4\"2\"", 42)).isFalse();
  }

  @Test
  void aMalformedHeaderNeverMatchesEvenIfItStartsWithTheVersion() {
    assertThat(EntityTags.matches("\"42\"garbage", 42)).isFalse();
    assertThat(EntityTags.matches("\"42\", junk", 42)).isFalse();
    assertThat(EntityTags.matches("junk, \"42\"", 42)).isFalse();
    assertThat(EntityTags.matches("*, \"42\"", 42)).isFalse();
  }
}
