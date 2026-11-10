package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppEnvironmentTest {

  @Test
  void holdsApplicationAndEnvironment() {
    final AppEnvironment appEnvironment = new AppEnvironment("billing", "prod");

    assertThat(appEnvironment.application()).isEqualTo("billing");
    assertThat(appEnvironment.environment()).isEqualTo("prod");
  }

  @Test
  void equalWhenBothCoordinatesMatch() {
    assertThat(new AppEnvironment("billing", "prod"))
        .isEqualTo(new AppEnvironment("billing", "prod"))
        .hasSameHashCodeAs(new AppEnvironment("billing", "prod"))
        .isNotEqualTo(new AppEnvironment("billing", "dev"))
        .isNotEqualTo(new AppEnvironment("shipping", "prod"));
  }

  @Test
  void rejectsNullApplication() {
    assertThatThrownBy(() -> new AppEnvironment(null, "prod"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("application");
  }

  @Test
  void rejectsNullEnvironment() {
    assertThatThrownBy(() -> new AppEnvironment("billing", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("environment");
  }
}
