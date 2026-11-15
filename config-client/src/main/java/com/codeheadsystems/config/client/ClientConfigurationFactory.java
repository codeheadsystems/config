package com.codeheadsystems.config.client;

import com.codeheadsystems.config.client.api.SettingsApi;
import com.codeheadsystems.config.client.invoker.ApiClient;
import com.codeheadsystems.config.client.invoker.ApiException;
import com.codeheadsystems.config.client.invoker.ApiResponse;
import com.codeheadsystems.config.client.model.ErrorMessage;
import com.codeheadsystems.config.client.model.Settings;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Configuration;
import com.codeheadsystems.config.model.ConfigurationFactory;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ConfigurationFactory} that reads the application's settings from the config service,
 * following the client rules in {@code openapi/config-api.yaml}:
 *
 * <ul>
 *   <li><b>Lookups are local.</b> They read the last settings received, so they are cheap and
 *       never wait on, or fail because of, the network.</li>
 *   <li><b>Polling follows the server.</b> A background thread checks again after the response's
 *       {@code max-age} (at least {@value #MINIMUM_INTERVAL_SECONDS} second), plus up to 10%
 *       random jitter so instances started together do not poll together. Each check is a
 *       conditional {@code GET}: {@code 304 Not Modified} when nothing changed, the new settings
 *       when something did. After {@code 429} or {@code 503} it waits at least
 *       {@code Retry-After}.</li>
 *   <li><b>Versions only move forward.</b> A response with an older version than the one held
 *       (from a server whose cache lags) is ignored.</li>
 *   <li><b>Errors never drop values.</b> On any failure ({@code 401}, {@code 403}, {@code 404},
 *       server errors, network errors) the last settings stay in use, the problem is logged when it
 *       starts and reported by {@link #problem()}, and checking continues. Settings count as
 *       {@linkplain #stale() stale} once no check has succeeded for {@code max-age} plus
 *       {@code stale-if-error}.</li>
 * </ul>
 *
 * <p>The bearer token is read for every request, from a supplier or a file, so a token the
 * platform renews (such as a Kubernetes projected service account token) is always current. On
 * {@code 401} the request is retried once at once if the token has changed since.
 *
 * <pre>{@code
 * try (ClientConfigurationFactory factory = ClientConfigurationFactory.builder()
 *     .with(Application.from("billing"))
 *     .with(Environment.from("prod"))
 *     .baseUri(URI.create("https://config.example.com"))
 *     .tokenFile(Path.of("/var/run/secrets/tokens/config-token"))
 *     .build()) {
 *   Configuration configuration = factory.configuration();
 *   configuration.get("database.url");
 * }
 * }</pre>
 *
 * <p>Optional {@linkplain Builder#defaults(Configuration) defaults} answer keys the settings do not
 * have, including before the first successful check.
 */
public final class ClientConfigurationFactory implements ConfigurationFactory {

  /** The shortest wait between checks, whatever {@code max-age} says. */
  public static final long MINIMUM_INTERVAL_SECONDS = 1;

  /** Connect and read timeout for each request when none is set. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(MINIMUM_INTERVAL_SECONDS);
  private static final double MAX_JITTER = 0.1;
  private static final Logger logger = LoggerFactory.getLogger(ClientConfigurationFactory.class);

  /** Before the first successful load: no values, and a version below every real one. */
  private static final Layer NOT_LOADED = new Layer(Map.of(), 0);

  private final Application application;
  private final Environment environment;
  private final ApiClient client;
  private final SettingsApi api;
  private final Supplier<String> tokens;
  private final Clock clock;
  private final DoubleSupplier jitter;
  private final Configuration configuration;
  private final ScheduledExecutorService scheduler; // null without automatic refresh

  private volatile Layer layer = NOT_LOADED;
  private volatile CachePolicy policy = CachePolicy.DEFAULT;
  private volatile Instant lastSuccess; // null until a check succeeds
  private volatile String problem; // null after a successful check
  private volatile String lastToken;
  private volatile Duration nextCheck = Duration.ZERO; // for tests: the wait the last check chose
  private boolean failing; // guarded by this; for logging transitions
  private boolean reportedStale; // guarded by this

  private ClientConfigurationFactory(final Builder builder, final Application application,
                                     final Environment environment) {
    this.application = application;
    this.environment = environment;
    this.tokens = builder.tokens;
    this.clock = builder.clock;
    this.jitter = builder.jitter;
    this.client = new ApiClient();
    client.updateBaseUri(builder.baseUri.toString());
    client.setHttpClientBuilder(HttpClient.newBuilder().connectTimeout(builder.timeout));
    client.setReadTimeout(builder.timeout);
    client.setRequestInterceptor(request -> {
      final String token = tokens.get();
      lastToken = token;
      request.header("Authorization", "Bearer " + token);
    });
    this.api = new SettingsApi(client);

    final Configuration.Builder values = Configuration.builder().with(key -> layer.find(key));
    if (builder.defaults != null) {
      values.with(builder.defaults);
    }
    this.configuration = values.build();

    final Duration first = check().nextCheck();
    if (builder.requireSettingsAtStartup && layer == NOT_LOADED) {
      throw new ConfigClientException("Cannot read " + describe() + " settings from the config service: " + problem);
    }
    this.scheduler = builder.autoRefresh ? startPolling(first) : null;
  }

  /**
   * Starts building a factory.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The configuration. The same instance every time, always reading the latest settings received;
   * configurations already handed out see a refresh.
   *
   * @return the configuration.
   */
  @Override
  public Configuration configuration() {
    return configuration;
  }

  /**
   * Checks the config service now, rather than waiting for the next scheduled check. Never throws
   * for a failure: it is logged, recorded in {@link #problem()}, and the current settings kept.
   *
   * @return true if new settings were loaded.
   */
  public boolean refresh() {
    return check().changed();
  }

  /**
   * Whether settings have been received from the config service at least once. False until then;
   * only the defaults, if any, are served.
   *
   * @return true once settings have been received.
   */
  public boolean settingsLoaded() {
    return layer != NOT_LOADED;
  }

  /**
   * The version of the settings being served.
   *
   * @return the version, or 0 if none have been received.
   */
  public long settingsVersion() {
    return layer.version();
  }

  /**
   * When a check last succeeded (settings received, or confirmed unchanged).
   *
   * @return the time, or empty if no check has succeeded.
   */
  public Optional<Instant> lastChecked() {
    return Optional.ofNullable(lastSuccess);
  }

  /**
   * Why the last check failed, such as {@code "403: Not allowed to read application billing in
   * environment prod"}, for logs and health checks.
   *
   * @return the problem, or empty if the last check succeeded.
   */
  public Optional<String> problem() {
    return Optional.ofNullable(problem);
  }

  /**
   * Whether the settings are stale: no check has succeeded for {@code max-age} plus
   * {@code stale-if-error} (one day by default), or none ever has. Stale settings are still
   * served; this is for health checks and alerts.
   *
   * @return true if stale.
   */
  public boolean stale() {
    final Instant success = lastSuccess;
    final CachePolicy current = policy;
    return success == null || clock.instant().isAfter(success.plus(current.maxAge()).plus(current.staleIfError()));
  }

  /**
   * Stops the background checks. The configuration keeps serving the last settings received.
   * Calling it again does nothing.
   */
  @Override
  public void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** The result of one check: whether settings changed, and when to check next. */
  private record Outcome(boolean changed, Duration nextCheck) {
  }

  private synchronized Outcome check() {
    final Outcome outcome = checkOnce();
    nextCheck = outcome.nextCheck();
    return outcome;
  }

  /** The wait the last check chose before the next one. */
  Duration nextCheck() {
    return nextCheck;
  }

  private Outcome checkOnce() {
    final Layer current = layer;
    final String ifNoneMatch = current == NOT_LOADED ? null : "\"" + current.version() + "\"";
    try {
      final ApiResponse<Settings> response = get(ifNoneMatch);
      policy = CachePolicy.parse(header(response.getHeaders(), "cache-control"));
      succeeded();
      return new Outcome(adopt(current, response.getData()), interval(policy.maxAge()));
    } catch (final ApiException e) {
      if (e.getCode() == 304) {
        policy = CachePolicy.parse(e.getResponseHeaders().firstValue("Cache-Control"));
        succeeded();
        return new Outcome(false, interval(policy.maxAge()));
      }
      failed(describe(e));
      return new Outcome(false, afterFailure(e));
    } catch (final RuntimeException e) {
      // The token supplier or file failed, before any request was sent.
      failed("cannot get a bearer token: " + e.getMessage());
      return new Outcome(false, interval(policy.maxAge()));
    }
  }

  private ApiResponse<Settings> get(final String ifNoneMatch) throws ApiException {
    try {
      return api.getSettingsWithHttpInfo(application.value(), environment.value(), ifNoneMatch);
    } catch (final ApiException e) {
      final String refused = lastToken;
      if (e.getCode() == 401 && !Objects.equals(refused, tokens.get())) {
        logger.debug("Token refused, and a new one is available; retrying");
        return api.getSettingsWithHttpInfo(application.value(), environment.value(), ifNoneMatch);
      }
      throw e;
    }
  }

  /** Adopts the settings if they are newer than the ones held. */
  private boolean adopt(final Layer current, final Settings settings) {
    final long version = settings.getVersion();
    if (current != NOT_LOADED && version <= current.version()) {
      if (version < current.version()) {
        logger.debug("Ignoring {} settings version {}: already serving version {}", describe(), version,
            current.version());
      }
      return false;
    }
    final Map<Key, Value> values = new HashMap<>();
    settings.getValues().forEach((key, value) -> values.put(Key.from(key), new Value(value)));
    layer = new Layer(Map.copyOf(values), version);
    logger.info("Loaded {} settings version {} ({} keys)", describe(), version, values.size());
    return true;
  }

  private void succeeded() {
    lastSuccess = clock.instant();
    problem = null;
    reportedStale = false;
    if (failing) {
      failing = false;
      logger.info("Config service reachable again for {}", describe());
    }
  }

  private void failed(final String why) {
    problem = why;
    if (!failing) {
      failing = true;
      logger.warn("Cannot check {} settings ({}); serving {}", describe(), why,
          layer == NOT_LOADED ? "defaults only" : "version " + layer.version());
    } else {
      logger.debug("Still cannot check {} settings: {}", describe(), why);
    }
    if (!reportedStale && stale() && layer != NOT_LOADED) {
      reportedStale = true;
      logger.warn("{} settings version {} are stale: no successful check since {}", describe(), layer.version(),
          lastSuccess);
    }
  }

  /** {@code Retry-After} on 429 and 503, if longer than the normal interval. */
  private Duration afterFailure(final ApiException e) {
    final Duration normal = interval(policy.maxAge());
    if (e.getCode() != 429 && e.getCode() != 503 || e.getResponseHeaders() == null) {
      return normal;
    }
    return e.getResponseHeaders().firstValue("Retry-After")
        .flatMap(ClientConfigurationFactory::seconds)
        .map(retryAfter -> retryAfter.compareTo(normal) > 0 ? retryAfter : normal)
        .orElse(normal);
  }

  /** The wait before the next check: at least a second, plus up to 10% jitter. */
  Duration interval(final Duration maxAge) {
    final Duration base = maxAge.compareTo(MINIMUM_INTERVAL) < 0 ? MINIMUM_INTERVAL : maxAge;
    return base.plusMillis((long) (base.toMillis() * MAX_JITTER * jitter.getAsDouble()));
  }

  private String describe() {
    return application.value() + "/" + environment.value();
  }

  private String describe(final ApiException e) {
    if (e.getCode() == 0) {
      final Throwable cause = e.getCause() == null ? e : e.getCause();
      return "cannot reach the config service: " + cause;
    }
    final String body = e.getResponseBody();
    String message = body == null ? "" : body;
    try {
      if (body != null && !body.isBlank()) {
        message = client.getObjectMapper().readValue(body, ErrorMessage.class).getMessage();
      }
    } catch (final RuntimeException notTheErrorModel) {
      // keep the raw body
    }
    return e.getCode() + ": " + message;
  }

  private static Optional<String> header(final Map<String, List<String>> headers, final String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty())
        .map(entry -> entry.getValue().get(0))
        .findFirst();
  }

  private static Optional<Duration> seconds(final String value) {
    try {
      final long seconds = Long.parseLong(value.trim());
      return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    } catch (final NumberFormatException e) {
      return Optional.empty(); // an HTTP date; rare enough to use the normal interval
    }
  }

  private ScheduledExecutorService startPolling(final Duration first) {
    final String name = "config-client-" + application.value() + "-" + environment.value();
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    });
    schedule(executor, first);
    return executor;
  }

  private void schedule(final ScheduledExecutorService executor, final Duration delay) {
    try {
      executor.schedule(() -> {
        Duration next;
        try {
          next = check().nextCheck();
        } catch (final RuntimeException e) {
          logger.error("Unexpected failure checking {} settings", describe(), e);
          next = interval(policy.maxAge());
        }
        schedule(executor, next);
      }, delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final RejectedExecutionException closed) {
      // the factory was closed
    }
  }

  /** One set of settings received. */
  private record Layer(Map<Key, Value> values, long version) {

    Optional<Value> find(final Key key) {
      return Optional.ofNullable(values.get(key));
    }
  }

  /**
   * Builds a {@link ClientConfigurationFactory}. Set the application, the environment, the
   * {@link #baseUri(URI)}, and a token ({@link #token(Supplier)} or {@link #tokenFile(Path)}).
   */
  public static final class Builder extends ConfigurationFactory.Builder<Builder, ClientConfigurationFactory> {

    private URI baseUri;
    private Supplier<String> tokens;
    private Configuration defaults;
    private Duration timeout = DEFAULT_TIMEOUT;
    private boolean autoRefresh = true;
    private boolean requireSettingsAtStartup;
    private Clock clock = Clock.systemUTC();
    private DoubleSupplier jitter = () -> ThreadLocalRandom.current().nextDouble();

    private Builder() {
    }

    /**
     * The config service, such as {@code https://config.example.com}. Use {@code https}: the
     * bearer token must only be sent over TLS.
     *
     * @param baseUri the service's base URI.
     * @return this builder.
     */
    public Builder baseUri(final URI baseUri) {
      this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
      return this;
    }

    /**
     * Where the bearer token comes from. Called before every request, so it can return a renewed
     * token; it must be cheap.
     *
     * @param tokens returns the current token.
     * @return this builder.
     */
    public Builder token(final Supplier<String> tokens) {
      this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
      return this;
    }

    /**
     * Reads the bearer token from a file before every request, such as a Kubernetes projected
     * service account token, which the kubelet replaces before it expires.
     *
     * @param file the token file.
     * @return this builder.
     */
    public Builder tokenFile(final Path file) {
      Objects.requireNonNull(file, "file must not be null");
      return token(() -> {
        try {
          return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (final IOException e) {
          throw new UncheckedIOException("Cannot read the token file " + file, e);
        }
      });
    }

    /**
     * Values for keys the settings do not have, and for every key until settings are first
     * received. A later call replaces them.
     *
     * @param defaults the defaults.
     * @return this builder.
     */
    public Builder defaults(final Configuration defaults) {
      this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
      return this;
    }

    /**
     * Connect and read timeout for each request; {@link #DEFAULT_TIMEOUT} if not set.
     *
     * @param timeout the timeout, positive.
     * @return this builder.
     */
    public Builder timeout(final Duration timeout) {
      Objects.requireNonNull(timeout, "timeout must not be null");
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive: " + timeout);
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Whether a background thread checks for changes (the default). With false, the application
     * calls {@link ClientConfigurationFactory#refresh()} itself.
     *
     * @param autoRefresh false to turn the background thread off.
     * @return this builder.
     */
    public Builder autoRefresh(final boolean autoRefresh) {
      this.autoRefresh = autoRefresh;
      return this;
    }

    /**
     * Whether {@link #build()} fails when the settings cannot be read at startup. By default
     * (false) the factory starts with the defaults, if any, and keeps trying; true makes it throw,
     * so the application never starts without its settings.
     *
     * @param requireSettingsAtStartup true to fail without settings at startup.
     * @return this builder.
     */
    public Builder requireSettingsAtStartup(final boolean requireSettingsAtStartup) {
      this.requireSettingsAtStartup = requireSettingsAtStartup;
      return this;
    }

    Builder clock(final Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    Builder jitter(final DoubleSupplier jitter) {
      this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * {@inheritDoc} Makes the first check and starts the background checks.
     *
     * @throws IllegalStateException if the base URI or the token is not set.
     * @throws ConfigClientException if {@link #requireSettingsAtStartup(boolean)} is set and the
     *                               settings cannot be read.
     */
    @Override
    protected ClientConfigurationFactory build(final Application application, final Environment environment) {
      if (baseUri == null) {
        throw new IllegalStateException("no baseUri set");
      }
      if (tokens == null) {
        throw new IllegalStateException("no token set: set token or tokenFile");
      }
      return new ClientConfigurationFactory(this, application, environment);
    }
  }
}
