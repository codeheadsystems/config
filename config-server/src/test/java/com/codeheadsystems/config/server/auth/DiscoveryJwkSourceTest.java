package com.codeheadsystems.config.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.config.testoidc.TestOidcIssuer;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiscoveryJwkSourceTest {

  private static final String ISSUER = "https://oidc.example.com";
  private static final long MINUTE = 60_000;
  private static final long DAY = 86_400_000;

  @Test
  void findsKeysThroughDiscoveryAndCachesThem() throws Exception {
    try (TestOidcIssuer issuer = TestOidcIssuer.start();
         DiscoveryJwkSource keys = new DiscoveryJwkSource(issuer.issuer().toString(),
             new DefaultResourceRetriever(1000, 1000), MINUTE, 5 * MINUTE, DAY)) {
      keys.check();
      keys.check();

      assertThat(issuer.discoveryRequests()).isEqualTo(1);
      assertThat(issuer.jwksRequests()).isEqualTo(1);
    }
  }

  @Test
  void refusesADiscoveryDocumentForAnotherIssuer() {
    final DiscoveryJwkSource keys = new DiscoveryJwkSource(ISSUER,
        serving("{\"issuer\":\"https://evil.example.com\",\"jwks_uri\":\"https://oidc.example.com/jwks\"}"),
        MINUTE, 5 * MINUTE, DAY);

    assertThatThrownBy(keys::check).isInstanceOf(KeySourceException.class).hasMessageContaining("different issuer");
  }

  @Test
  void refusesAnInsecureOrMissingJwksUri() {
    for (final String document : new String[] {
        "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"http://oidc.example.com/jwks\"}",
        "{\"issuer\":\"" + ISSUER + "\"}"}) {
      final DiscoveryJwkSource keys = new DiscoveryJwkSource(ISSUER, serving(document), MINUTE, 5 * MINUTE, DAY);
      assertThatThrownBy(keys::check).isInstanceOf(KeySourceException.class).hasMessageContaining("jwks_uri");
    }
  }

  @Test
  void aFailedDiscoveryIsNotRetriedImmediately() {
    final AtomicInteger calls = new AtomicInteger();
    final DiscoveryJwkSource keys = new DiscoveryJwkSource(ISSUER, url -> {
      calls.incrementAndGet();
      throw new IOException("connection refused");
    }, MINUTE, 5 * MINUTE, DAY);

    assertThatThrownBy(keys::check).isInstanceOf(KeySourceException.class).hasMessageContaining("connection refused");
    assertThatThrownBy(keys::check).isInstanceOf(KeySourceException.class)
        .hasMessageContaining("connection refused").hasMessageContaining("failed recently");
    assertThat(calls).hasValue(1);
  }

  @Test
  void closingBeforeUseIsHarmless() {
    final DiscoveryJwkSource keys = new DiscoveryJwkSource(ISSUER, serving("{}"), MINUTE, 5 * MINUTE, DAY);

    assertThatCode(keys::close).doesNotThrowAnyException();
  }

  private static ResourceRetriever serving(final String document) {
    return (URL url) -> new Resource(document, "application/json");
  }
}
