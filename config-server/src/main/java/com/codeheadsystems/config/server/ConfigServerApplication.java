package com.codeheadsystems.config.server;

import com.codeheadsystems.config.datastore.ConfigDatastore;
import com.codeheadsystems.config.server.api.ApiExceptionMapper;
import com.codeheadsystems.config.server.api.EofExceptionMapper;
import com.codeheadsystems.config.server.api.HeadContentLengthFilter;
import com.codeheadsystems.config.server.api.JsonErrorHandler;
import com.codeheadsystems.config.server.api.UnexpectedExceptionMapper;
import com.codeheadsystems.config.server.api.WebApplicationExceptionMapper;
import com.codeheadsystems.config.server.auth.TokenVerifier;
import com.codeheadsystems.config.server.dagger.DaggerServerComponent;
import com.codeheadsystems.config.server.dagger.ServerComponent;
import io.dropwizard.core.Application;
import io.dropwizard.core.server.AbstractServerFactory;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.lifecycle.Managed;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;

/**
 * The config service. Run it with {@code server <config.yml>}; see {@code config-server/config.yml}
 * for the settings.
 */
public class ConfigServerApplication extends Application<ConfigServerConfiguration> {

  private volatile ConfigDatastore datastore;

  /**
   * Runs the service.
   *
   * @param args Dropwizard's command line, such as {@code server config.yml}.
   * @throws Exception if it cannot start.
   */
  public static void main(final String[] args) throws Exception {
    new ConfigServerApplication().run(args);
  }

  @Override
  public String getName() {
    return "config-server";
  }

  @Override
  public void run(final ConfigServerConfiguration configuration, final Environment environment) {
    // Every error uses the API's {"message": ...} body: Jersey's from the mappers registered
    // below, Jetty's own (before Jersey) from JsonErrorHandler.
    if (configuration.getServerFactory() instanceof AbstractServerFactory server) {
      server.setRegisterDefaultExceptionMappers(false);
    }
    environment.lifecycle().addServerLifecycleListener(server -> server.setErrorHandler(new JsonErrorHandler()));

    final ConfigDatastore opened = configuration.getDatastore().open();
    try {
      wire(configuration, environment, opened);
    } catch (final RuntimeException e) {
      opened.close();
      throw e;
    }
    datastore = opened;
  }

  private void wire(final ConfigServerConfiguration configuration, final Environment environment,
                    final ConfigDatastore opened) {
    final ServerComponent component =
        DaggerServerComponent.factory().create(configuration, opened, environment.metrics());
    final TokenVerifier verifier = component.tokenVerifier();
    environment.lifecycle().manage(new Managed() {
      @Override
      public void stop() {
        try {
          verifier.close();
        } finally {
          opened.close();
        }
      }
    });
    environment.healthChecks().register("datastore", new DatastoreHealthCheck(opened));
    for (final String issuer : verifier.issuerNames()) {
      environment.healthChecks().register("issuer-" + issuer, new IssuerHealthCheck(verifier, issuer));
    }
    environment.servlets().addFilter("head-content-length", new HeadContentLengthFilter())
        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/v1/*");
    environment.jersey().register(component.bearerTokenFilter());
    environment.jersey().register(component.settingsResource());
    environment.jersey().register(new ApiExceptionMapper());
    environment.jersey().register(new WebApplicationExceptionMapper());
    environment.jersey().register(new EofExceptionMapper());
    environment.jersey().register(new UnexpectedExceptionMapper());
  }

  /**
   * The datastore being served, once {@link #run} has opened it. For tests, which write to it
   * through the library the way an application would.
   *
   * @return the datastore.
   */
  ConfigDatastore datastore() {
    return datastore;
  }
}
