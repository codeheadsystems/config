package com.codeheadsystems.config.testoidc;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds one JWT access token from a {@link TestOidcIssuer}. By default the token is valid: signed
 * with the issuer's current key, for {@link TestOidcIssuer#AUDIENCE}, expiring in five minutes.
 * The other methods change that, mostly to make tokens a verifier must refuse.
 */
public final class TokenBuilder {

  private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

  private final TestOidcIssuer.SigningKey issuerKey;
  private final Map<String, Object> header = new LinkedHashMap<>();
  private final Map<String, Object> claims = new LinkedHashMap<>();
  private Signing signing = Signing.ISSUER_KEY;

  private enum Signing { ISSUER_KEY, UNTRUSTED_KEY, NONE, HMAC_WITH_PUBLIC_KEY }

  TokenBuilder(final String issuer, final String subject, final TestOidcIssuer.SigningKey issuerKey) {
    this.issuerKey = issuerKey;
    final Instant now = Instant.now();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    header.put("kid", issuerKey.keyId());
    claims.put("iss", issuer);
    claims.put("sub", Objects.requireNonNull(subject, "subject"));
    claims.put("aud", TestOidcIssuer.AUDIENCE);
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(DEFAULT_LIFETIME).getEpochSecond());
    claims.put("jti", UUID.randomUUID().toString());
  }

  /**
   * Sets {@code aud}. One audience is written as a string, several as an array; both are valid.
   *
   * @param audiences the audiences.
   * @return this builder.
   */
  public TokenBuilder audience(final String... audiences) {
    claims.put("aud", audiences.length == 1 ? audiences[0] : List.of(audiences));
    return this;
  }

  /**
   * Sets {@code iss}, such as to an issuer the verifier does not trust. The token is still signed
   * with this issuer's key.
   *
   * @param issuer the issuer claim.
   * @return this builder.
   */
  public TokenBuilder issuer(final String issuer) {
    claims.put("iss", issuer);
    return this;
  }

  /**
   * Sets {@code exp} relative to now. A negative duration makes an expired token.
   *
   * @param lifetime how long from now the token is valid.
   * @return this builder.
   */
  public TokenBuilder expiresIn(final Duration lifetime) {
    claims.put("exp", Instant.now().plus(lifetime).getEpochSecond());
    return this;
  }

  /**
   * Sets {@code nbf}, the time before which the token must be refused.
   *
   * @param notBefore the time.
   * @return this builder.
   */
  public TokenBuilder notBefore(final Instant notBefore) {
    claims.put("nbf", notBefore.getEpochSecond());
    return this;
  }

  /**
   * Sets any claim, replacing the default if there is one.
   *
   * @param name  the claim name.
   * @param value a value Jackson can write as JSON.
   * @return this builder.
   */
  public TokenBuilder claim(final String name, final Object value) {
    claims.put(name, value);
    return this;
  }

  /**
   * Removes a claim, such as {@code sub} or {@code exp}, to test that it is required.
   *
   * @param name the claim name.
   * @return this builder.
   */
  public TokenBuilder without(final String name) {
    claims.remove(name);
    return this;
  }

  /**
   * Sets the {@code typ} header, such as {@code at+jwt} for an RFC 9068 access token.
   *
   * @param type the type.
   * @return this builder.
   */
  public TokenBuilder type(final String type) {
    header.put("typ", type);
    return this;
  }

  /**
   * Sets the {@code kid} header, such as to a key ID the issuer never published. The token is
   * still signed with the issuer's current key.
   *
   * @param keyId the key ID.
   * @return this builder.
   */
  public TokenBuilder keyId(final String keyId) {
    header.put("kid", keyId);
    return this;
  }

  /**
   * Signs with a new key that the issuer never publishes, keeping the current key's {@code kid}:
   * a forged token whose signature must not verify.
   *
   * @return this builder.
   */
  public TokenBuilder signedWithUntrustedKey() {
    signing = Signing.UNTRUSTED_KEY;
    return this;
  }

  /**
   * Makes an unsigned token ({@code "alg": "none"}, empty signature), which a verifier must refuse
   * (RFC 8725).
   *
   * @return this builder.
   */
  public TokenBuilder unsigned() {
    signing = Signing.NONE;
    return this;
  }

  /**
   * Signs with HS256, keyed with the issuer's public key (its X.509 encoding): the algorithm
   * confusion attack, which a verifier that lets the token choose the algorithm would accept.
   *
   * @return this builder.
   */
  public TokenBuilder hmacWithPublicKey() {
    signing = Signing.HMAC_WITH_PUBLIC_KEY;
    return this;
  }

  /**
   * Makes the token.
   *
   * @return the compact JWS: {@code header.claims.signature}, each part base64url.
   */
  public String sign() {
    final Map<String, Object> jwsHeader = new LinkedHashMap<>(header);
    if (signing == Signing.NONE) {
      jwsHeader.put("alg", "none");
      jwsHeader.remove("kid");
    } else if (signing == Signing.HMAC_WITH_PUBLIC_KEY) {
      jwsHeader.put("alg", "HS256");
    }
    final String signingInput = TestOidcIssuer.json(jwsHeader) + "." + TestOidcIssuer.json(claims);
    return signingInput + "." + signature(TestOidcIssuer.ascii(signingInput));
  }

  private String signature(final byte[] signingInput) {
    try {
      return switch (signing) {
        case NONE -> "";
        case HMAC_WITH_PUBLIC_KEY -> hs256(issuerKey.keyPair().getPublic().getEncoded(), signingInput);
        case UNTRUSTED_KEY -> rs256(TestOidcIssuer.SigningKey.generate(), signingInput);
        case ISSUER_KEY -> rs256(issuerKey, signingInput);
      };
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign the token", e);
    }
  }

  private static String hs256(final byte[] secret, final byte[] signingInput)
      throws GeneralSecurityException {
    final Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    return TestOidcIssuer.base64url(mac.doFinal(signingInput));
  }

  private static String rs256(final TestOidcIssuer.SigningKey key, final byte[] signingInput)
      throws GeneralSecurityException {
    final Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key.keyPair().getPrivate());
    signer.update(signingInput);
    return TestOidcIssuer.base64url(signer.sign());
  }
}
