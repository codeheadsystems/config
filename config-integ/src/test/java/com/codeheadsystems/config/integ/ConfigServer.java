package com.codeheadsystems.config.integ;

import com.codeheadsystems.config.server.ConfigServerApplication;
import com.codeheadsystems.config.server.ConfigServerConfiguration;
import io.dropwizard.testing.DropwizardTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real config service, started from a configuration file the way it is deployed: a datastore
 * by name and options, and one trusted issuer. The file is written as JSON, which YAML accepts, so
 * option values need no YAML quoting.
 */
final class ConfigServer implements AutoCloseable {

  static final String ISSUER_NAME = "integ";
  static final String AUDIENCE = "config-server";

  private final DropwizardTestSupport<ConfigServerConfiguration> support;
  private final Path file;

  ConfigServer(final String datastore, final Map<String, String> options, final URI issuer) {
    final Map<String, Object> configuration = Map.of(
        "server", Map.of(
            "applicationConnectors", List.of(Map.of("type", "http", "port", 0)),
            "adminConnectors", List.of(Map.of("type", "http", "port", 0))),
        "logging", Map.of("level", "WARN"),
        "datastore", Map.of("name", datastore, "options", options),
        "auth", Map.of("issuers", List.of(Map.of(
            "name", ISSUER_NAME, "issuer", issuer.toString(), "audience", AUDIENCE))),
        // No server-side caching, so a change made through the library is visible at once.
        "cache", Map.of("versionTtl", "0s", "metadataTtl", "0s"));
    try {
      file = Files.createTempFile("config-server-", ".yml");
      Files.writeString(file, JsonMapper.builder().build().writeValueAsString(configuration));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    support = new DropwizardTestSupport<>(ConfigServerApplication.class, file.toString());
    try {
      support.before();
    } catch (final Exception e) {
      close();
      throw new IllegalStateException("config-server did not start on " + datastore, e);
    }
  }

  URI uri() {
    return URI.create("http://localhost:" + support.getLocalPort());
  }

  URI adminUri() {
    return URI.create("http://localhost:" + support.getAdminPort());
  }

  @Override
  public void close() {
    support.after();
    try {
      Files.deleteIfExists(file);
    } catch (final IOException e) {
      // a temp file; nothing to do
    }
  }
}
