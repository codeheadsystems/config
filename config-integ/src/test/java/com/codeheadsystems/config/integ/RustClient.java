package com.codeheadsystems.config.integ;

import com.codeheadsystems.config.datastore.testkit.TestEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Rust client ({@code config_client::ConfigClient}), run as rust/config-integ-driver against
 * the real server. Gradle builds the driver and names it in the {@code config.integ.rustDriver}
 * system property. Without it (no cargo) the calling test skips, or fails on CI.
 *
 * <p>The driver reads its bearer token from a file on every request, as a service reading a
 * projected token file does; {@link #token(String)} replaces it.
 */
final class RustClient implements AutoCloseable {

  private static final String DRIVER_PROPERTY = "config.integ.rustDriver";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long ANSWER_SECONDS = 30;

  private final Process process;
  private final Path tokenFile;
  private final Writer commands;
  private final BufferedReader answers;
  private State state;

  /** What the Rust client reported after its last command. */
  record State(boolean changed, long version, Map<String, String> settings, Optional<String> problem) {
  }

  private RustClient(final Process process, final Path tokenFile) {
    this.process = process;
    this.tokenFile = tokenFile;
    this.commands = process.outputWriter(StandardCharsets.UTF_8);
    this.answers = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
  }

  /** Skips the calling test (or fails it on CI) if the driver was not built. */
  static void requireDriver() {
    final String driver = System.getProperty(DRIVER_PROPERTY);
    TestEnvironment.require(driver != null && Files.isExecutable(Path.of(driver)),
        "cargo (to build rust/config-integ-driver)");
  }

  /**
   * Starts the Rust client, which makes its first check and requires settings at startup.
   *
   * @throws IllegalStateException with the client's error if it could not start.
   */
  static RustClient start(final URI baseUri, final String application, final String environment,
                          final String token) {
    requireDriver();
    final Path tokenFile;
    final Process process;
    try {
      tokenFile = Files.createTempFile("config-integ-token-", ".jwt");
      Files.writeString(tokenFile, token);
      process = new ProcessBuilder(List.of(System.getProperty(DRIVER_PROPERTY), baseUri.toString(),
          application, environment, tokenFile.toString()))
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final RustClient client = new RustClient(process, tokenFile);
    try {
      client.state = client.read();
    } catch (final RuntimeException e) {
      client.close();
      throw e;
    }
    return client;
  }

  /** Checks the config service now; {@link State#changed()} says whether new settings loaded. */
  State refresh() {
    return command("refresh");
  }

  /** What the client reported last, without checking again. */
  State state() {
    return state;
  }

  /** Replaces the token the client sends from its next request on. */
  void token(final String token) {
    try {
      Files.writeString(tokenFile, token);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private State command(final String command) {
    try {
      commands.write(command + "\n");
      commands.flush();
    } catch (final IOException e) {
      throw new UncheckedIOException("the Rust client exited", e);
    }
    state = read();
    return state;
  }

  private State read() {
    final String line;
    try {
      line = CompletableFuture.supplyAsync(() -> {
        try {
          return answers.readLine();
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      }).get(ANSWER_SECONDS, TimeUnit.SECONDS);
    } catch (final TimeoutException e) {
      throw new IllegalStateException("the Rust client did not answer in " + ANSWER_SECONDS + "s", e);
    } catch (final ExecutionException | InterruptedException e) {
      throw new IllegalStateException("cannot read the Rust client's answer", e);
    }
    if (line == null) {
      throw new IllegalStateException("the Rust client exited");
    }
    final JsonNode answer = JSON.readTree(line);
    if (!answer.path("ok").asBoolean()) {
      throw new IllegalStateException("Rust client: " + answer.path("error").asString());
    }
    final Map<String, String> settings = new LinkedHashMap<>();
    answer.path("settings").properties().forEach(entry -> settings.put(entry.getKey(), entry.getValue().asString()));
    final JsonNode problem = answer.path("problem");
    return new State(answer.path("changed").asBoolean(), answer.path("version").asLong(), Map.copyOf(settings),
        problem.isString() ? Optional.of(problem.asString()) : Optional.empty());
  }

  @Override
  public void close() {
    try {
      commands.write("quit\n");
      commands.flush();
    } catch (final IOException e) {
      // already exited
    }
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (final InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    try {
      Files.deleteIfExists(tokenFile);
    } catch (final IOException e) {
      // a temp file; nothing to do
    }
  }
}
