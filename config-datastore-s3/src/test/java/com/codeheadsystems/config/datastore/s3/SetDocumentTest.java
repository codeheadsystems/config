package com.codeheadsystems.config.datastore.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.datastore.ConfigDatastoreException;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetDocumentTest {

  private static byte[] utf8(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void roundTrips() {
    final SetDocument document = new SetDocument(7, Map.of(
      Key.from("database.url"), new Value("jdbc:postgresql://prod/billing"),
      Key.from("empty"), new Value(""),
      Key.from("unicode"), new Value("héllo \"quoted\" ✓\n")));

    assertThat(SetDocument.fromJson(document.toJson(), "k")).isEqualTo(document);
  }

  @Test
  void writesTheDocumentedShapeWithSortedKeys() {
    final SetDocument document = new SetDocument(3, Map.of(
      Key.from("timeout"), new Value("30s"),
      Key.from("database.url"), new Value("x")));

    assertThat(new String(document.toJson(), StandardCharsets.UTF_8))
      .isEqualTo("{\"version\":3,\"values\":{\"database.url\":\"x\",\"timeout\":\"30s\"}}");
  }

  @Test
  void tombstoneHasNoValues() {
    assertThat(new String(new SetDocument(4, Map.of()).toJson(), StandardCharsets.UTF_8))
      .isEqualTo("{\"version\":4,\"values\":{}}");
  }

  @Test
  void missingValuesReadAsEmpty() {
    assertThat(SetDocument.fromJson(utf8("{\"version\":2}"), "k").values()).isEmpty();
  }

  @Test
  void malformedJsonIsADatastoreError() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("not json"), "config/sets/_a/_b/settings"))
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("config/sets/_a/_b/settings");
  }

  @Test
  void invalidVersionIsADatastoreError() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("{\"version\":0,\"values\":{}}"), "k"))
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("invalid version");
  }

  @Test
  void nullValueIsADatastoreError() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("{\"version\":1,\"values\":{\"a\":null}}"), "k"))
      .isInstanceOf(ConfigDatastoreException.class);
  }

  @Test
  void invalidKeyIsRejected() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("{\"version\":1,\"values\":{\"bad key\":\"v\"}}"), "k"))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
