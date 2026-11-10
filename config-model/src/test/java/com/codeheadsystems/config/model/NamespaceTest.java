package com.codeheadsystems.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NamespaceTest {

  @Test
  void fromCreatesNamespace() {
    assertThat(Namespace.from("database")).isEqualTo(new Namespace("database"));
    assertThat(Namespace.from("database").name()).isEqualTo("database");
  }

  @Test
  void andJoinsWithSeparator() {
    assertThat(Namespace.from("database").and("pool").name()).isEqualTo("database.pool");
  }

  @Test
  void andChains() {
    assertThat(Namespace.from("a").and("b").and("c").name()).isEqualTo("a.b.c");
  }

  @Test
  void andDoesNotModifyOriginal() {
    final Namespace database = Namespace.from("database");

    database.and("pool");

    assertThat(database.name()).isEqualTo("database");
  }

  @Test
  void separatorIsDot() {
    assertThat(Namespace.SEPARATOR).isEqualTo(".");
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> new Namespace(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Namespace.from(null)).isInstanceOf(NullPointerException.class);
  }
}
