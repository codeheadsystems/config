package com.codeheadsystems.config.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A config service that answers with scripted responses, in order, and records the requests. With
 * nothing scripted it answers {@code 500}.
 */
final class FakeConfigService implements AutoCloseable {

  static final String CACHE_CONTROL = "private, max-age=30, stale-while-revalidate=30, stale-if-error=86400";

  /** A request as received. */
  record Request(String method, String path, String authorization, String ifNoneMatch) {
  }

  /** A scripted response. */
  record Response(int status, Map<String, String> headers, String body) {
  }

  private final HttpServer server;
  private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  FakeConfigService() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  List<Request> requests() {
    return requests;
  }

  FakeConfigService settings(final long version, final Map<String, String> values) {
    return settings(version, values, CACHE_CONTROL);
  }

  FakeConfigService settings(final long version, final Map<String, String> values, final String cacheControl) {
    final StringBuilder json = new StringBuilder("{\"application\":\"billing\",\"environment\":\"prod\",\"version\":")
        .append(version).append(",\"values\":{");
    values.forEach((key, value) -> json.append('"').append(key).append("\":\"").append(value).append("\","));
    if (!values.isEmpty()) {
      json.setLength(json.length() - 1);
    }
    json.append("}}");
    responses.add(new Response(200, Map.of("ETag", "\"" + version + "\"", "Config-Version", Long.toString(version),
        "Cache-Control", cacheControl), json.toString()));
    return this;
  }

  FakeConfigService notModified(final long version) {
    responses.add(new Response(304, Map.of("ETag", "\"" + version + "\"", "Config-Version", Long.toString(version),
        "Cache-Control", CACHE_CONTROL), null));
    return this;
  }

  FakeConfigService error(final int status, final String message) {
    return error(status, message, Map.of());
  }

  FakeConfigService error(final int status, final String message, final Map<String, String> headers) {
    responses.add(new Response(status, headers, "{\"message\":\"" + message + "\"}"));
    return this;
  }

  private void handle(final HttpExchange exchange) throws IOException {
    try {
      requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          exchange.getRequestHeaders().getFirst("If-None-Match")));
      final Response response = responses.poll();
      final Response sent = response == null ? new Response(500, Map.of(), "{\"message\":\"Nothing scripted\"}")
          : response;
      sent.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      if (sent.body() == null) {
        exchange.sendResponseHeaders(sent.status(), -1);
        return;
      }
      final byte[] body = sent.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(sent.status(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
