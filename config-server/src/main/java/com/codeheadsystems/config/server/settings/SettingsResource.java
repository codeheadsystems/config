package com.codeheadsystems.config.server.settings;

import com.codeheadsystems.config.datastore.SetId;
import com.codeheadsystems.config.datastore.Snapshot;
import com.codeheadsystems.config.datastore.Versions;
import com.codeheadsystems.config.model.Application;
import com.codeheadsystems.config.model.Environment;
import com.codeheadsystems.config.model.Key;
import com.codeheadsystems.config.model.Value;
import com.codeheadsystems.config.server.api.ApiException;
import com.codeheadsystems.config.server.auth.Caller;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code /v1/applications/{application}/environments/{environment}/settings}, as described in
 * {@code openapi/config-api.yaml}. The bearer token was already checked
 * ({@link com.codeheadsystems.config.server.auth.BearerTokenFilter}); each method then checks, in
 * order, the names in the path ({@code 400}), access ({@code 403}), and existence ({@code 404}).
 */
@Singleton
@Path("/v1/applications/{application}/environments/{environment}/settings")
@Produces(MediaType.APPLICATION_JSON)
public final class SettingsResource {

  /** The response header carrying the set's version as a plain integer. */
  public static final String CONFIG_VERSION = "Config-Version";

  private final SettingsService service;

  /**
   * Creates the resource.
   *
   * @param service reads sets.
   */
  @Inject
  public SettingsResource(final SettingsService service) {
    this.service = service;
  }

  /**
   * {@code getSettings}: the whole set, or {@code 304} if {@code If-None-Match} names its version.
   *
   * @param application the application.
   * @param environment the environment.
   * @param ifNoneMatch the {@code ETag} the client has, if any.
   * @param security    the verified caller.
   * @return the response.
   */
  @GET
  public Response getSettings(@PathParam("application") final String application,
                              @PathParam("environment") final String environment,
                              @HeaderParam(HttpHeaders.IF_NONE_MATCH) final String ifNoneMatch,
                              @Context final SecurityContext security) {
    final Target target = authorize(parse(application, Application::from, "application"),
        parse(environment, Environment::from, "environment"), security);
    if (ifNoneMatch != null) {
      final long current = service.version(target.settings());
      if (current != Versions.NONE && EntityTags.matches(ifNoneMatch, current)) {
        return notModified(current, target);
      }
    }
    final Snapshot snapshot = service.load(target.settings()).orElseThrow(() -> noSettings(target));
    if (EntityTags.matches(ifNoneMatch, snapshot.version())) {
      return notModified(snapshot.version(), target);
    }
    return versioned(Response.ok(SettingsResponse.of(snapshot)), snapshot.version(), target).build();
  }

  /**
   * {@code getSettingsVersion}: the version in headers, no body.
   *
   * @param application the application.
   * @param environment the environment.
   * @param ifNoneMatch the {@code ETag} the client has, if any.
   * @param security    the verified caller.
   * @return the response.
   */
  @HEAD
  public Response getSettingsVersion(@PathParam("application") final String application,
                                     @PathParam("environment") final String environment,
                                     @HeaderParam(HttpHeaders.IF_NONE_MATCH) final String ifNoneMatch,
                                     @Context final SecurityContext security) {
    final Target target = authorize(parse(application, Application::from, "application"),
        parse(environment, Environment::from, "environment"), security);
    final long version = service.version(target.settings());
    if (version == Versions.NONE) {
      throw noSettings(target);
    }
    if (EntityTags.matches(ifNoneMatch, version)) {
      return notModified(version, target);
    }
    // HeadContentLengthFilter removes the Content-Length: 0 Jersey would add (RFC 9110, 8.6).
    return versioned(Response.ok(), version, target).build();
  }

  /**
   * {@code getSetting}: one key's value, with the set's version.
   *
   * @param application the application.
   * @param environment the environment.
   * @param key         the key.
   * @param security    the verified caller.
   * @return the response.
   */
  @GET
  @Path("{key}")
  public Response getSetting(@PathParam("application") final String application,
                             @PathParam("environment") final String environment,
                             @PathParam("key") final String key,
                             @Context final SecurityContext security) {
    final Application app = parse(application, Application::from, "application");
    final Environment env = parse(environment, Environment::from, "environment");
    final Key parsedKey = parse(key, Key::from, "key");
    final Target target = authorize(app, env, security);
    final Snapshot snapshot = service.load(target.settings()).orElseThrow(() -> noSettings(target));
    final Value value = snapshot.find(parsedKey).orElseThrow(() -> ApiException.notFound(
        "No setting " + parsedKey.name() + " for application " + app.value() + " in environment " + env.value()));
    return versioned(Response.ok(new SettingResponse(parsedKey.name(), value.value())), snapshot.version(), target)
        .build();
  }

  /** The checked request: which set, and the cache header its METADATA asks for. */
  private record Target(Application application, Environment environment, SetId settings, String cacheControl) {
  }

  private Target authorize(final Application application, final Environment environment,
                           final SecurityContext security) {
    if (!(security.getUserPrincipal() instanceof Caller caller)) {
      throw ApiException.missingToken();
    }
    final Map<Key, Value> metadata = service.metadata(application, environment);
    if (!AccessPolicy.allows(metadata, caller)) {
      throw ApiException.forbidden(
          "Not allowed to read application " + application.value() + " in environment " + environment.value());
    }
    return new Target(application, environment, SetId.settings(application, environment),
        CachePolicy.cacheControl(metadata, service.cacheConfig(),
            "application " + application.value() + " in environment " + environment.value()));
  }

  private static <T> T parse(final String value, final Function<String, T> parser, final String what) {
    try {
      return parser.apply(value);
    } catch (final IllegalArgumentException e) {
      throw ApiException.badRequest("Invalid " + what + " '" + value + "'");
    }
  }

  private static ApiException noSettings(final Target target) {
    return ApiException.notFound("No settings for application " + target.application().value()
        + " in environment " + target.environment().value());
  }

  private static Response notModified(final long version, final Target target) {
    return versioned(Response.notModified(), version, target).build();
  }

  private static Response.ResponseBuilder versioned(final Response.ResponseBuilder response, final long version,
                                                    final Target target) {
    return response.tag(new EntityTag(Long.toString(version)))
        .header(CONFIG_VERSION, version)
        .header(HttpHeaders.CACHE_CONTROL, target.cacheControl());
  }
}
