package com.codeheadsystems.config.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.config.server.auth.IssuerUrls;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.BaseValidator;
import jakarta.validation.Validator;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Settings that would turn off a protection are refused at startup, not discovered in production. */
class ConfigValidationTest {

  private static final Validator VALIDATOR = BaseValidator.newValidator();

  @Test
  void defaultsAreValid() {
    assertThat(violations(new ConfigServerConfiguration())).isEmpty();
  }

  @Test
  void issuersMustBeHttpsExceptOnLoopback() {
    assertThat(IssuerUrls.isSecure(URI.create("https://oidc.example.com"))).isTrue();
    assertThat(IssuerUrls.isSecure(URI.create("https://oidc.example.com/realms/prod"))).isTrue();
    assertThat(IssuerUrls.isSecure(URI.create("http://127.0.0.1:8080"))).isTrue();
    assertThat(IssuerUrls.isSecure(URI.create("http://localhost:8080"))).isTrue();
    assertThat(IssuerUrls.isSecure(URI.create("http://[::1]:8080"))).isTrue();

    assertThat(IssuerUrls.isSecure(URI.create("http://oidc.internal"))).isFalse();
    assertThat(IssuerUrls.isSecure(URI.create("oidc.example.com"))).isFalse();
    assertThat(IssuerUrls.isSecure(URI.create("ftp://oidc.example.com"))).isFalse();
    assertThat(IssuerUrls.isSecure(URI.create("https://oidc.example.com#fragment"))).isFalse();
    assertThat(IssuerUrls.isSecure(null)).isFalse();
  }

  @Test
  void anInsecureIssuerFailsValidation() {
    final ConfigServerConfiguration configuration = withIssuer("http://oidc.internal");

    assertThat(violations(configuration)).anyMatch(message -> message.contains("must be an absolute https URL"));
    assertThat(violations(withIssuer("https://oidc.example.com"))).isEmpty();
  }

  @Test
  void timeoutsAndRefreshIntervalsMustBePositive() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getAuth().setTimeout(Duration.seconds(0));
    configuration.getAuth().setKeyRefreshInterval(Duration.seconds(0));

    assertThat(violations(configuration)).hasSize(2);
  }

  @Test
  void theKeyRefreshIntervalMustBeShorterThanTheKeyCache() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getAuth().setKeyRefreshInterval(Duration.minutes(10));

    assertThat(violations(configuration)).anyMatch(message -> message.contains("shorter than keyCacheTtl"));
  }

  @Test
  void anUnboundedClockSkewFailsValidation() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getAuth().setClockSkew(Duration.hours(1));

    assertThat(violations(configuration)).hasSize(1);
  }

  @Test
  void missingListsAndValuesFailValidationRatherThanThrowing() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getAuth().setIssuers(null);
    final Map<String, String> options = new HashMap<>();
    options.put("bucket", null);
    configuration.getDatastore().setOptions(options);

    assertThat(violations(configuration)).hasSize(2);

    final List<IssuerConfig> issuers = new java.util.ArrayList<>();
    issuers.add(null);
    configuration.getAuth().setIssuers(issuers);
    configuration.getDatastore().setOptions(null);
    assertThat(violations(configuration)).hasSize(2);
  }

  @Test
  void cacheSecondsMustFitIn32Bits() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getCache().setDefaultMaxAge(Integer.MAX_VALUE + 1L);

    assertThat(violations(configuration)).hasSize(1);
  }

  @Test
  void rateLimitMustBePositive() {
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getRateLimit().setBurst(0);
    configuration.getRateLimit().setRequestsPerMinute(0);

    assertThat(violations(configuration)).hasSize(2);
  }

  private static ConfigServerConfiguration withIssuer(final String url) {
    final IssuerConfig issuer = new IssuerConfig();
    issuer.setName("prod");
    issuer.setIssuer(URI.create(url));
    issuer.setAudience("config-server");
    final ConfigServerConfiguration configuration = new ConfigServerConfiguration();
    configuration.getAuth().setIssuers(List.of(issuer));
    return configuration;
  }

  private static Set<String> violations(final ConfigServerConfiguration configuration) {
    return VALIDATOR.validate(configuration).stream()
        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
        .collect(Collectors.toSet());
  }
}
