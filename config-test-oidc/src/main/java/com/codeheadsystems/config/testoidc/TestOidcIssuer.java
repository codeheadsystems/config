package com.codeheadsystems.config.testoidc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OpenID Connect issuer for tests, running in-process on the loopback interface.
 *
 * <p>It serves what a token verifier needs and nothing more:
 *
 * <ul>
 *   <li>{@code /.well-known/openid-configuration}: the discovery document, naming the JWKS.</li>
 *   <li>{@code /jwks}: the public keys, as a JWK Set (RFC 7517).</li>
 * </ul>
 *
 * <p>Tokens are made directly with {@link #token(String)}; there is no token endpoint, because
 * the config service only verifies tokens and the way a client gets one is the issuer's business.
 * Tokens are RS256-signed JWTs (RFC 7519) with {@code iss}, {@code sub}, {@code aud},
 * {@code iat}, {@code exp} and {@code jti}. The builder can also make invalid tokens (expired,
 * wrong audience or issuer, unknown or untrusted key, unsigned, HMAC-signed), so a verifier's
 * rejections can be tested.
 *
 * <pre>{@code
 * try (TestOidcIssuer issuer = TestOidcIssuer.start()) {
 *   String token = issuer.token("system:serviceaccount:billing:billing").sign();
 *   // configure the server to trust issuer.issuer() with audience TestOidcIssuer.AUDIENCE
 * }
 * }</pre>
 */
public final class TestOidcIssuer implements AutoCloseable {

  /** The audience tokens carry unless told otherwise. */
  public static final String AUDIENCE = "config-server";

  static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
  static final String JWKS_PATH = "/jwks";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private final HttpServer server;
  private final URI issuer;
  private final AtomicInteger discoveryRequests = new AtomicInteger();
  private final AtomicInteger jwksRequests = new AtomicInteger();
  private final Object lock = new Object();
  private final List<SigningKey> published = new ArrayList<>();
  private SigningKey current;

  private TestOidcIssuer(final HttpServer server) {
    this.server = server;
    this.issuer = URI.create("http://" + server.getAddress().getAddress().getHostAddress()
        + ":" + server.getAddress().getPort());
    this.current = SigningKey.generate();
    this.published.add(current);
  }

  /**
   * Starts an issuer on a free port of the loopback interface.
   *
   * @return the running issuer; close it to stop it.
   */
  public static TestOidcIssuer start() {
    final HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (final IOException e) {
      throw new UncheckedIOException("Could not start the test OIDC issuer", e);
    }
    final TestOidcIssuer issuer = new TestOidcIssuer(server);
    server.createContext(DISCOVERY_PATH, issuer::discovery);
    server.createContext(JWKS_PATH, issuer::jwks);
    server.start();
    return issuer;
  }

  /**
   * The issuer identifier, the value of {@code iss}, such as {@code http://127.0.0.1:41234}.
   * Discovery is at this URL plus {@code /.well-known/openid-configuration}.
   *
   * @return the issuer URL.
   */
  public URI issuer() {
    return issuer;
  }

  /**
   * Where the public keys are served.
   *
   * @return the JWKS URL.
   */
  public URI jwksUri() {
    return URI.create(issuer + JWKS_PATH);
  }

  /**
   * The key ID ({@code kid}) new tokens are signed with.
   *
   * @return the current key ID.
   */
  public String keyId() {
    synchronized (lock) {
      return current.keyId();
    }
  }

  /**
   * Starts signing with a new key. The JWKS keeps the old keys too, the way a real issuer rotates,
   * so tokens signed before the rotation still verify. A verifier that cached the JWKS sees a
   * {@code kid} it does not know and must fetch the JWKS again.
   *
   * @return the new key ID.
   */
  public String rotateKey() {
    synchronized (lock) {
      current = SigningKey.generate();
      published.add(current);
      return current.keyId();
    }
  }

  /**
   * Stops publishing every key but the current one, as an issuer does once old tokens have
   * expired. Tokens signed with the removed keys no longer verify.
   */
  public void retireOldKeys() {
    synchronized (lock) {
      published.retainAll(List.of(current));
    }
  }

  /**
   * Starts a token for a subject, valid for five minutes, for {@link #AUDIENCE}.
   *
   * @param subject the {@code sub} claim: who the client is.
   * @return a builder; call {@link TokenBuilder#sign()} to get the token.
   */
  public TokenBuilder token(final String subject) {
    final SigningKey key;
    synchronized (lock) {
      key = current;
    }
    return new TokenBuilder(issuer.toString(), subject, key);
  }

  /**
   * How many times the discovery document has been fetched, to test a verifier's caching.
   *
   * @return the count.
   */
  public int discoveryRequests() {
    return discoveryRequests.get();
  }

  /**
   * How many times the JWKS has been fetched, to test a verifier's caching and refetching.
   *
   * @return the count.
   */
  public int jwksRequests() {
    return jwksRequests.get();
  }

  /** Stops the server. */
  @Override
  public void close() {
    server.stop(0);
  }

  private void discovery(final HttpExchange exchange) throws IOException {
    discoveryRequests.incrementAndGet();
    final Map<String, Object> document = new LinkedHashMap<>();
    document.put("issuer", issuer.toString());
    document.put("jwks_uri", jwksUri().toString());
    // Required by OpenID Connect Discovery 1.0; not served, since tokens are made in-process.
    document.put("authorization_endpoint", issuer + "/authorize");
    document.put("response_types_supported", List.of("id_token"));
    document.put("subject_types_supported", List.of("public"));
    document.put("id_token_signing_alg_values_supported", List.of("RS256"));
    respond(exchange, document);
  }

  private void jwks(final HttpExchange exchange) throws IOException {
    jwksRequests.incrementAndGet();
    final List<Map<String, String>> keys = new ArrayList<>();
    synchronized (lock) {
      for (final SigningKey key : published) {
        keys.add(key.jwk());
      }
    }
    respond(exchange, Map.of("keys", keys));
  }

  private static void respond(final HttpExchange exchange, final Object body) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      final byte[] json = JSON.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, json.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(json);
      }
    } finally {
      exchange.close();
    }
  }

  static String base64url(final byte[] bytes) {
    return BASE64URL.encodeToString(bytes);
  }

  static String json(final Object value) {
    return base64url(JSON.writeValueAsBytes(value));
  }

  /** An RSA key pair and its key ID. */
  record SigningKey(String keyId, KeyPair keyPair) {

    static SigningKey generate() {
      try {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new SigningKey(UUID.randomUUID().toString(), generator.generateKeyPair());
      } catch (final GeneralSecurityException e) {
        throw new IllegalStateException("RSA is not available", e);
      }
    }

    Map<String, String> jwk() {
      final RSAPublicKey key = (RSAPublicKey) keyPair.getPublic();
      final Map<String, String> jwk = new LinkedHashMap<>();
      jwk.put("kty", "RSA");
      jwk.put("use", "sig");
      jwk.put("alg", "RS256");
      jwk.put("kid", keyId);
      jwk.put("n", base64url(unsigned(key.getModulus())));
      jwk.put("e", base64url(unsigned(key.getPublicExponent())));
      return jwk;
    }

    /** JWK integers are unsigned big-endian, without BigInteger's leading sign byte. */
    private static byte[] unsigned(final BigInteger value) {
      final byte[] bytes = value.toByteArray();
      if (bytes.length > 1 && bytes[0] == 0) {
        final byte[] trimmed = new byte[bytes.length - 1];
        System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
        return trimmed;
      }
      return bytes;
    }
  }

  static byte[] ascii(final String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
