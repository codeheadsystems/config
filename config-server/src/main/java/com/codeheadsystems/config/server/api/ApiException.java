package com.codeheadsystems.config.server.api;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An error the API describes: its status, message and headers become the response, through
 * {@link ApiExceptionMapper}. Use the factory methods; each matches a response in the spec.
 */
public final class ApiException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final int status;
  private final transient Map<String, String> headers;

  private ApiException(final int status, final String message, final Map<String, String> headers) {
    super(Objects.requireNonNull(message, "message"));
    this.status = status;
    this.headers = Map.copyOf(headers);
  }

  /**
   * {@code 400}: a name in the path is not valid.
   *
   * @param message what is wrong.
   * @return the exception.
   */
  public static ApiException badRequest(final String message) {
    return new ApiException(400, message, Map.of());
  }

  /**
   * {@code 401} with {@code WWW-Authenticate: Bearer}: no bearer token was sent. RFC 6750 says not
   * to include an error code in this case.
   *
   * @return the exception.
   */
  public static ApiException missingToken() {
    return new ApiException(401, "A bearer token is required", Map.of("WWW-Authenticate", "Bearer"));
  }

  /**
   * {@code 401} with {@code WWW-Authenticate: Bearer error="invalid_token"}.
   *
   * @param message why the token was refused; also the {@code error_description}.
   * @return the exception.
   */
  public static ApiException invalidToken(final String message) {
    return new ApiException(401, message, Map.of("WWW-Authenticate",
        "Bearer error=\"invalid_token\", error_description=\"" + message + "\""));
  }

  /**
   * {@code 403}, without {@code WWW-Authenticate}: the token is valid, but access is granted by the
   * server, so neither a new token nor more scope would help (RFC 6750's {@code insufficient_scope}
   * would tell OAuth clients to ask for more scope).
   *
   * @param message what was refused.
   * @return the exception.
   */
  public static ApiException forbidden(final String message) {
    return new ApiException(403, message, Map.of());
  }

  /**
   * {@code 404}.
   *
   * @param message what does not exist.
   * @return the exception.
   */
  public static ApiException notFound(final String message) {
    return new ApiException(404, message, Map.of());
  }

  /**
   * {@code 429} with {@code Retry-After}: the token's request limit is used up.
   *
   * @param retryAfterSeconds how long the client should wait.
   * @return the exception.
   */
  public static ApiException tooManyRequests(final long retryAfterSeconds) {
    return new ApiException(429, "Too many requests", Map.of("Retry-After", Long.toString(retryAfterSeconds)));
  }

  /**
   * {@code 503} with {@code Retry-After}: a datastore or token issuer cannot be reached.
   *
   * @param message what cannot be reached.
   * @param retryAfterSeconds how long the client should wait.
   * @return the exception.
   */
  public static ApiException unavailable(final String message, final long retryAfterSeconds) {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Retry-After", Long.toString(retryAfterSeconds));
    return new ApiException(503, message, headers);
  }

  /**
   * The HTTP status.
   *
   * @return the status.
   */
  public int status() {
    return status;
  }

  /**
   * Headers to send with the error.
   *
   * @return the headers.
   */
  public Map<String, String> headers() {
    return headers;
  }
}
