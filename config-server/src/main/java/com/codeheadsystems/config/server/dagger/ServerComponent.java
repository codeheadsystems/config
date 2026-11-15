package com.codeheadsystems.config.server.dagger;

import com.codahale.metrics.MetricRegistry;
import com.codeheadsystems.config.datastore.ConfigReader;
import com.codeheadsystems.config.server.ConfigServerConfiguration;
import com.codeheadsystems.config.server.auth.BearerTokenFilter;
import com.codeheadsystems.config.server.auth.TokenVerifier;
import com.codeheadsystems.config.server.settings.SettingsResource;
import dagger.BindsInstance;
import dagger.Component;
import jakarta.inject.Singleton;

/** The objects Dropwizard registers with Jersey. */
@Singleton
@Component(modules = ServerModule.class)
public interface ServerComponent {

  /**
   * The settings resource.
   *
   * @return the resource.
   */
  SettingsResource settingsResource();

  /**
   * The filter that checks bearer tokens.
   *
   * @return the filter.
   */
  BearerTokenFilter bearerTokenFilter();

  /**
   * The token verifier, for issuer health checks and closing at shutdown.
   *
   * @return the verifier.
   */
  TokenVerifier tokenVerifier();

  /** Creates the component. */
  @Component.Factory
  interface Factory {

    /**
     * Creates the component.
     *
     * @param configuration the configuration file.
     * @param reader        the datastore, read-only: the server never writes.
     * @param metrics       Dropwizard's metric registry.
     * @return the component.
     */
    ServerComponent create(@BindsInstance ConfigServerConfiguration configuration,
                           @BindsInstance ConfigReader reader,
                           @BindsInstance MetricRegistry metrics);
  }
}
