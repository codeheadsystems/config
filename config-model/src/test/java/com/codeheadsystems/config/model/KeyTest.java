package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class KeyTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "a",
      "url",
      "database.url",
      "database.pool.size",
      "my-key_2",
      "UPPER.lower.Mixed",
      "0",
      "-",
      "_",
      "a.b.c.d.e"
  })
  void acceptsPropertiesSafeKeys(final String name) {
    assertThat(Key.from(name).name()).isEqualTo(name);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      ".a",        // leading separator
      "a.",        // trailing separator
      ".",         // separator only
      "a..b",      // empty segment
      "a b",       // whitespace: properties splits key and value on it
      " a",
      "a ",
      "a\tb",
      "a\nb",
      "a=b",       // properties key/value separators
      "a:b",
      "#a",        // properties comment markers
      "!a",
      "a\\b",      // properties escape character
      "café", // non-ASCII: properties stores it as a \\u escape
      "a/b",
      "a*"
  })
  void rejectsKeysThatAreNotPropertiesSafe(final String name) {
    assertThatThrownBy(() -> Key.from(name)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "\n"})
  void rejectsBlankKeys(final String name) {
    assertThatThrownBy(() -> Key.from(name)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidKeyMessageNamesTheKeyAndTheRule() {
    assertThatThrownBy(() -> Key.from("a b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid key 'a b': use letters, digits, '_' or '-', with '.' only between segments");
  }

  @Test
  void blankKeyGetsTheSameMessageAsAnyInvalidKey() {
    assertThatThrownBy(() -> Key.from(""))
        .hasMessage("Invalid key '': use letters, digits, '_' or '-', with '.' only between segments");
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new Key(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Key.from(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromIsTheConstructor() {
    assertThat(Key.from("database")).isEqualTo(new Key("database"));
  }

  @Test
  void equalWhenNamesMatch() {
    assertThat(Key.from("database.url"))
        .isEqualTo(Key.from("database.url"))
        .hasSameHashCodeAs(Key.from("database.url"))
        .isNotEqualTo(Key.from("database.URL"));
  }

  @Test
  void andStringJoinsWithSeparator() {
    assertThat(Key.from("database").and("pool").name()).isEqualTo("database.pool");
  }

  @Test
  void andKeyJoinsWithSeparator() {
    assertThat(Key.from("database").and(Key.from("pool.size")).name()).isEqualTo("database.pool.size");
  }

  @Test
  void andChains() {
    assertThat(Key.from("a").and("b").and("c").name()).isEqualTo("a.b.c");
  }

  @Test
  void andDoesNotModifyOriginal() {
    final Key database = Key.from("database");

    database.and("pool");

    assertThat(database.name()).isEqualTo("database");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".pool", "pool.", "a b"})
  void andRejectsSuffixThatWouldProduceAnInvalidKey(final String suffix) {
    assertThatThrownBy(() -> Key.from("database").and(suffix)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void andRejectsNullKey() {
    assertThatThrownBy(() -> Key.from("database").and((Key) null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void separatorIsDot() {
    assertThat(Key.SEPARATOR).isEqualTo(".");
  }
}
