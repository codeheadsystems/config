package com.codeheadsystems.config.server.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

/**
 * Errors Jetty answers itself, before a request reaches Jersey (such as {@code 400} for an
 * ambiguous path encoding like {@code %2F}), in the API's {@code {"message": ...}} body rather than
 * an HTML page. The message is the status's reason phrase; details are never sent.
 */
public final class JsonErrorHandler extends ErrorHandler {

  @Override
  protected void generateResponse(final Request request, final Response response, final int code,
                                  final String message, final Throwable cause, final Callback callback) {
    final String reason = HttpStatus.getMessage(code);
    final byte[] body = ("{\"message\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
    response.write(true, ByteBuffer.wrap(body), callback);
  }
}
