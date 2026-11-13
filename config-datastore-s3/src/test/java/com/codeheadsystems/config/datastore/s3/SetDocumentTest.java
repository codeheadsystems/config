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
      .isEqualTo("{\"format\":1,\"version\":3,\"values\":{\"database.url\":\"x\",\"timeout\":\"30s\"}}");
  }

  @Test
  void everyLiveDocumentIsLongerThanAMarker() {
    assertThat(new SetDocument(1, Map.of()).toJson()).hasSizeGreaterThan(SetDocument.MARKER_BYTES);
  }

  @Test
  void markersHaveAFixedSizeAndDifferPerVersion() {
    assertThat(SetDocument.marker(1)).hasSize(SetDocument.MARKER_BYTES);
    assertThat(SetDocument.marker(Long.MAX_VALUE)).hasSize(SetDocument.MARKER_BYTES);
    assertThat(SetDocument.marker(1)).isNotEqualTo(SetDocument.marker(2));
    assertThat(new String(SetDocument.marker(255), StandardCharsets.US_ASCII)).isEqualTo("00000000000000ff");
  }

  @Test
  void unknownFieldsAreIgnored() {
    final SetDocument read = SetDocument.fromJson(
      utf8("{\"format\":1,\"version\":2,\"values\":{\"a\":\"b\"},\"addedLater\":{\"x\":1}}"), "k");

    assertThat(read).isEqualTo(new SetDocument(2, Map.of(Key.from("a"), new Value("b"))));
  }

  @Test
  void aNewerFormatIsRefusedWithAClearMessage() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("{\"format\":2,\"version\":1,\"values\":{}}"), "k"))
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("format 2")
      .hasMessageContaining("upgrade");
  }

  @Test
  void aMissingFormatIsRefused() {
    assertThatThrownBy(() -> SetDocument.fromJson(utf8("{\"version\":1,\"values\":{}}"), "k"))
      .isInstanceOf(ConfigDatastoreException.class)
      .hasMessageContaining("format");
  }

  @Test
  void missingValuesReadAsEmpty() {
    assertThat(SetDocument.fromJson(utf8("{\"format\":1,\"version\":2}"), "k").values()).isEmpty();
  }

  @Test
  void corruptObjectsAreDatastoreErrorsNamingTheObject() {
    for (final String body : new String[] {
      "not json", "null", "{\"format\":1,\"version\":0,\"values\":{}}",
      "{\"format\":1,\"version\":1,\"values\":{\"a\":null}}",
      "{\"format\":1,\"version\":1,\"values\":{\"bad key\":\"v\"}}"}) {
      assertThatThrownBy(() -> SetDocument.fromJson(utf8(body), "config/sets/a/b/settings"))
        .as(body)
        .isInstanceOf(ConfigDatastoreException.class)
        .hasMessageContaining("config/sets/a/b/settings");
    }
  }
}
