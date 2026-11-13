package com.codeheadsystems.config.datastore.testkit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class TestEnvironmentTest {

  @Test
  void anAvailableRequirementDoesNothing() {
    assertThatCode(() -> TestEnvironment.require(true, "Docker", false)).doesNotThrowAnyException();
    assertThatCode(() -> TestEnvironment.require(true, "Docker", true)).doesNotThrowAnyException();
  }

  @Test
  void aMissingRequirementSkipsOffCi() {
    assertThatThrownBy(() -> TestEnvironment.require(false, "Docker", false))
      .isInstanceOf(TestAbortedException.class)
      .hasMessageContaining("Docker");
  }

  @Test
  void aMissingRequirementFailsOnCi() {
    assertThatThrownBy(() -> TestEnvironment.require(false, "Docker", true))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("Docker")
      .hasMessageContaining("CI");
  }
}
