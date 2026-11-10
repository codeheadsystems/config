package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValueTest {

  @Test
  void holdsValue() {
    assertThat(new Value("jdbc:postgresql://db/config").value()).isEqualTo("jdbc:postgresql://db/config");
  }

  @Test
  void emptyStringIsAValue() {
    assertThat(new Value("").value()).isEmpty();
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new Value(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromWrapsNonNull() {
    assertThat(Value.from("x")).contains(new Value("x"));
  }

  @Test
  void fromNullIsEmpty() {
    assertThat(Value.from(null)).isEmpty();
  }
}
