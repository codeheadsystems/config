package com.codeheadsystems.config.datastore.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import org.junit.jupiter.api.Test;

class S3KeysTest {

  private static final Application BILLING = Application.from("billing");
  private static final Environment PROD = Environment.from("prod");
  private final S3Keys keys = new S3Keys("config/");

  @Test
  void setKeysFollowTheLayout() {
    assertThat(keys.set(SetId.settings(BILLING, PROD))).isEqualTo("config/sets/billing/prod/settings");
    assertThat(keys.set(SetId.metadata(BILLING, PROD))).isEqualTo("config/sets/billing/prod/metadata");
  }

  @Test
  void listingPrefixesNest() {
    assertThat(keys.setsPrefix()).isEqualTo("config/sets/");
    assertThat(keys.applicationPrefix(BILLING)).isEqualTo("config/sets/billing/");
    assertThat(keys.environmentPrefix(BILLING, PROD)).isEqualTo("config/sets/billing/prod/");
    assertThat(keys.set(SetId.settings(BILLING, PROD))).startsWith(keys.environmentPrefix(BILLING, PROD));
  }

  @Test
  void prefixGetsATrailingSlash() {
    assertThat(new S3Keys("config").prefix()).isEqualTo("config/");
    assertThat(new S3Keys("a/b/").prefix()).isEqualTo("a/b/");
    assertThat(new S3Keys("").prefix()).isEmpty();
    assertThat(new S3Keys("").set(SetId.settings(BILLING, PROD))).isEqualTo("sets/billing/prod/settings");
  }

  @Test
  void probesAreOutsideTheSetsAndUnique() {
    final String probe = keys.probe();

    assertThat(probe).startsWith("config/_validate/").doesNotStartWith(keys.setsPrefix());
    assertThat(keys.probe()).isNotEqualTo(probe);
    assertThat(keys.parse(probe)).isEmpty();
  }

  @Test
  void parseRoundTripsEverySetKey() {
    for (final SetId id : new SetId[] {SetId.settings(BILLING, PROD), SetId.metadata(BILLING, PROD),
      SetId.settings(Application.from("billing-api_2"), Environment.from("us-east-1"))}) {
      assertThat(keys.parse(keys.set(id))).contains(id);
    }
  }

  @Test
  void parseIgnoresKeysThatAreNotSets() {
    assertThat(keys.parse("config/sets/billing/prod/other")).isEmpty();
    assertThat(keys.parse("config/sets/billing/prod/SETTINGS")).isEmpty();
    assertThat(keys.parse("config/sets/billing/prod")).isEmpty();
    assertThat(keys.parse("config/sets/billing/prod/settings/extra")).isEmpty();
    assertThat(keys.parse("config/sets/Billing/prod/settings")).isEmpty(); // not a valid name
    assertThat(keys.parse("config/sets//prod/settings")).isEmpty();
    assertThat(keys.parse("elsewhere/sets/billing/prod/settings")).isEmpty();
  }
}
