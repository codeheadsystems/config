package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApplicationEnvironmentTest {

  @Test
  void applicationHoldsValue() {
    assertThat(Application.from("billing").value()).isEqualTo("billing");
  }

  @Test
  void applicationEquality() {
    assertThat(Application.from("billing"))
      .isEqualTo(new Application("billing"))
      .hasSameHashCodeAs(new Application("billing"))
      .isNotEqualTo(Application.from("shipping"));
  }

  @Test
  void applicationRejectsNull() {
    assertThatThrownBy(() -> Application.from(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("value cannot be null");
  }

  @Test
  void environmentHoldsValue() {
    assertThat(Environment.from("prod").value()).isEqualTo("prod");
  }

  @Test
  void environmentEquality() {
    assertThat(Environment.from("prod"))
      .isEqualTo(new Environment("prod"))
      .hasSameHashCodeAs(new Environment("prod"))
      .isNotEqualTo(Environment.from("dev"));
  }

  @Test
  void environmentRejectsNull() {
    assertThatThrownBy(() -> Environment.from(null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("value cannot be null");
  }

  @Test
  void applicationAndEnvironmentWithTheSameValueAreNotEqual() {
    assertThat((Object) Application.from("prod")).isNotEqualTo(Environment.from("prod"));
  }
}
