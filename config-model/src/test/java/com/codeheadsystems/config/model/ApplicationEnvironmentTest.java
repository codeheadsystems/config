package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
      .hasMessage("application must not be null");
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
      .hasMessage("environment must not be null");
  }

  @Test
  void applicationAndEnvironmentWithTheSameValueAreNotEqual() {
    assertThat((Object) Application.from("prod")).isNotEqualTo(Environment.from("prod"));
  }

  @Test
  void validNamesAreAccepted() {
    for (final String name : List.of("billing", "billing-api", "billing_api", "a", "9lives", "us-east-1",
      "a".repeat(63))) {
      assertThat(Application.from(name).value()).isEqualTo(name);
      assertThat(Environment.from(name).value()).isEqualTo(name);
    }
  }

  @Test
  void invalidNamesAreRejected() {
    for (final String name : List.of("", " ", "Billing", "PROD", "-billing", "_billing", "billing/api",
      "billing.api", "billing api", "héllo", "..", "a".repeat(64))) {
      assertThatThrownBy(() -> Application.from(name)).as("application '%s'", name)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid application");
      assertThatThrownBy(() -> Environment.from(name)).as("environment '%s'", name)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid environment");
    }
  }

  @Test
  void bothUseTheSameRule() {
    assertThat(Application.VALIDATOR.pattern()).isEqualTo(Environment.VALIDATOR.pattern());
  }
}
