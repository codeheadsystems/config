package com.codeheadsystems.config.testoidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class TestOidcIssuerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private TestOidcIssuer issuer;

  @BeforeEach
  void startIssuer() {
    issuer = TestOidcIssuer.start();
  }

  @AfterEach
  void stopIssuer() {
    issuer.close();
  }

  @Test
  void discoveryNamesTheIssuerAndItsJwks() throws Exception {
    final Map<String, Object> discovery =
        get(URI.create(issuer.issuer() + "/.well-known/openid-configuration"));

    assertThat(discovery)
        .containsEntry("issuer", issuer.issuer().toString())
        .containsEntry("jwks_uri", issuer.jwksUri().toString())
        .containsEntry("id_token_signing_alg_values_supported", List.of("RS256"))
        .containsKeys("authorization_endpoint", "response_types_supported", "subject_types_supported");
    assertThat(issuer.issuer().getHost()).isEqualTo("127.0.0.1");
    assertThat(issuer.discoveryRequests()).isEqualTo(1);
  }

  @Test
  void aDefaultTokenVerifiesAgainstTheJwksAndCarriesTheStandardClaims() throws Exception {
    final String token = issuer.token("system:serviceaccount:billing:billing").sign();

    assertThat(verify(token)).isTrue();
    assertThat(header(token)).containsEntry("alg", "RS256").containsEntry("typ", "JWT")
        .containsEntry("kid", issuer.keyId());
    final Map<String, Object> claims = claims(token);
    assertThat(claims)
        .containsEntry("iss", issuer.issuer().toString())
        .containsEntry("sub", "system:serviceaccount:billing:billing")
        .containsEntry("aud", TestOidcIssuer.AUDIENCE)
        .containsKeys("iat", "jti");
    final long now = Instant.now().getEpochSecond();
    assertThat(((Number) claims.get("exp")).longValue()).isBetween(now + 290, now + 310);
    assertThat(issuer.jwksRequests()).isEqualTo(1);
  }

  @Test
  void claimsCanBeChanged() throws Exception {
    final Instant later = Instant.now().plusSeconds(600);
    final String token = issuer.token("client")
        .audience("config-server", "other")
        .issuer("https://elsewhere.example")
        .expiresIn(Duration.ofSeconds(-60))
        .notBefore(later)
        .claim("scope", "settings")
        .without("jti")
        .type("at+jwt")
        .sign();

    assertThat(verify(token)).isTrue();
    assertThat(header(token)).containsEntry("typ", "at+jwt");
    final Map<String, Object> claims = claims(token);
    assertThat(claims)
        .containsEntry("aud", List.of("config-server", "other"))
        .containsEntry("iss", "https://elsewhere.example")
        .containsEntry("scope", "settings")
        .doesNotContainKey("jti");
    assertThat(((Number) claims.get("exp")).longValue()).isLessThan(Instant.now().getEpochSecond());
    assertThat(((Number) claims.get("nbf")).longValue()).isEqualTo(later.getEpochSecond());
  }

  @Test
  void anUntrustedKeyDoesNotVerify() throws Exception {
    final String token = issuer.token("client").signedWithUntrustedKey().sign();

    assertThat(header(token)).containsEntry("kid", issuer.keyId());
    assertThat(verify(token)).isFalse();
  }

  @Test
  void anUnknownKeyIdIsNotInTheJwks() throws Exception {
    final String token = issuer.token("client").keyId("never-published").sign();

    assertThat(verify(token)).isFalse();
  }

  @Test
  void unsignedTokensHaveAlgNoneAndNoSignature() {
    final String token = issuer.token("client").unsigned().sign();

    assertThat(token).endsWith(".");
    assertThat(header(token)).containsEntry("alg", "none").doesNotContainKey("kid");
  }

  @Test
  void hmacTokensUseTheAlgorithmConfusionAttack() throws Exception {
    final String token = issuer.token("client").hmacWithPublicKey().sign();

    assertThat(header(token)).containsEntry("alg", "HS256").containsEntry("kid", issuer.keyId());
    assertThat(verify(token)).isFalse();
  }

  @Test
  void rotationPublishesBothKeysUntilTheOldOnesAreRetired() throws Exception {
    final String before = issuer.token("client").sign();
    final String oldKey = issuer.keyId();

    final String newKey = issuer.rotateKey();
    final String after = issuer.token("client").sign();

    assertThat(newKey).isNotEqualTo(oldKey).isEqualTo(issuer.keyId());
    assertThat(header(after)).containsEntry("kid", newKey);
    assertThat(verify(before)).isTrue();
    assertThat(verify(after)).isTrue();

    issuer.retireOldKeys();

    assertThat(verify(before)).isFalse();
    assertThat(verify(after)).isTrue();
  }

  @Test
  void onlyGetIsServed() throws Exception {
    final HttpResponse<String> response = HTTP.send(
        HttpRequest.newBuilder(issuer.jwksUri()).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(405);
  }

  /** What a verifier does: find the token's kid in the JWKS and check the RS256 signature. */
  private boolean verify(final String token) throws Exception {
    final String[] parts = token.split("\\.", -1);
    final Optional<PublicKey> key = publicKey((String) header(token).get("kid"));
    if (key.isEmpty() || !"RS256".equals(header(token).get("alg"))) {
      return false;
    }
    final Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(key.get());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
  }

  private Optional<PublicKey> publicKey(final String keyId) throws Exception {
    @SuppressWarnings("unchecked") // JWKS "keys" is an array of JSON objects
    final List<Map<String, String>> keys = (List<Map<String, String>>) get(issuer.jwksUri()).get("keys");
    for (final Map<String, String> jwk : keys) {
      if (jwk.get("kid").equals(keyId)) {
        assertThat(jwk).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig");
        final RSAPublicKeySpec spec = new RSAPublicKeySpec(
            new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n"))),
            new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e"))));
        return Optional.of(KeyFactory.getInstance("RSA").generatePublic(spec));
      }
    }
    return Optional.empty();
  }

  private static Map<String, Object> header(final String token) {
    return part(token, 0);
  }

  private static Map<String, Object> claims(final String token) {
    return part(token, 1);
  }

  private static Map<String, Object> part(final String token, final int index) {
    return JSON.readValue(Base64.getUrlDecoder().decode(token.split("\\.", -1)[index]), OBJECT);
  }

  private static Map<String, Object> get(final URI uri) throws Exception {
    final HttpResponse<byte[]> response =
        HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    return JSON.readValue(response.body(), OBJECT);
  }
}
