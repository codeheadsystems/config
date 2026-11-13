package com.codeheadsystems.config.datastore.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.SetType;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import java.util.List;
import org.junit.jupiter.api.Test;

class S3KeysTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private final S3Keys keys = new S3Keys("config/");

  @Test
  void liveAndTombstoneKeysFollowTheLayout() {
    assertThat(keys.live(SetId.settings(BILLING, PROD))).isEqualTo("config/sets/_billing/_prod/settings");
    assertThat(keys.live(SetId.metadata(BILLING, PROD))).isEqualTo("config/sets/_billing/_prod/metadata");
    assertThat(keys.tombstone(SetId.settings(BILLING, PROD))).isEqualTo("config/tombstones/_billing/_prod/settings");
  }

  @Test
  void tombstonesAreOutsideTheListedSets() {
    assertThat(keys.tombstone(SetId.settings(BILLING, PROD))).doesNotStartWith(keys.applicationsPrefix());
  }

  @Test
  void listingPrefixesNestCorrectly() {
    assertThat(keys.applicationsPrefix()).isEqualTo("config/sets/");
    assertThat(keys.applicationPrefix(BILLING)).isEqualTo("config/sets/_billing/");
    assertThat(keys.environmentPrefix(BILLING, PROD)).isEqualTo("config/sets/_billing/_prod/");
    assertThat(keys.live(SetId.settings(BILLING, PROD))).startsWith(keys.environmentPrefix(BILLING, PROD));
  }

  @Test
  void prefixGetsATrailingSlash() {
    assertThat(new S3Keys("config").prefix()).isEqualTo("config/");
    assertThat(new S3Keys("a/b/").prefix()).isEqualTo("a/b/");
    assertThat(new S3Keys("").prefix()).isEmpty();
    assertThat(new S3Keys("").live(SetId.settings(BILLING, PROD))).isEqualTo("sets/_billing/_prod/settings");
  }

  @Test
  void awkwardNamesRoundTrip() {
    for (final String name : List.of("billing", "", "a/b", "a b", "a+b", "100%", "_leading", "héllo ✓", "a%2Fb", "..")) {
      final String segment = S3Keys.segment(name);
      assertThat(segment).as(name).startsWith("_").doesNotContain("/");
      assertThat(S3Keys.decode(segment)).as(name).contains(name);
    }
  }

  @Test
  void slashInANameCannotCreateALevel() {
    final Application tricky = Application.from("billing/_prod");

    assertThat(keys.applicationPrefix(tricky)).isEqualTo("config/sets/_billing%2F_prod/");
  }

  @Test
  void nameInDecodesOurPrefixesAndIgnoresOthers() {
    final String parent = keys.applicationsPrefix();

    assertThat(keys.nameIn(parent, "config/sets/_billing/")).contains("billing");
    assertThat(keys.nameIn(parent, "config/sets/_a%2Fb/")).contains("a/b");
    assertThat(keys.nameIn(parent, "config/sets/notours/")).isEmpty();
    assertThat(keys.nameIn(parent, "elsewhere/_billing/")).isEmpty();
    assertThat(keys.nameIn(parent, "config/sets/_bad%zz/")).isEmpty();
  }

  @Test
  void typeInRecognisesOnlySetObjects() {
    final String parent = keys.environmentPrefix(BILLING, PROD);

    assertThat(keys.typeIn(parent, parent + "settings")).contains(SetType.SETTINGS);
    assertThat(keys.typeIn(parent, parent + "metadata")).contains(SetType.METADATA);
    assertThat(keys.typeIn(parent, parent + "SETTINGS")).isEmpty();
    assertThat(keys.typeIn(parent, parent + "other")).isEmpty();
    assertThat(keys.typeIn(parent, "elsewhere/settings")).isEmpty();
  }
}
