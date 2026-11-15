package com.codeheadsystems.config.server.dagger;

import com.codeheadsystems.config.server.CacheConfig;
import com.codeheadsystems.config.server.ConfigServerConfiguration;
import com.codeheadsystems.config.server.RateLimitConfig;
import com.codeheadsystems.config.server.auth.TokenVerifier;
import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;

/** Builds the objects that need more than an {@code @Inject} constructor. */
@Module
public final class ServerModule {

  private ServerModule() {
  }

  /**
   * The cache section, for the settings service.
   *
   * @param configuration the configuration file.
   * @return the cache section.
   */
  @Provides
  static CacheConfig cacheConfig(final ConfigServerConfiguration configuration) {
    return configuration.getCache();
  }

  /**
   * The rate limit section, for the rate limiter.
   *
   * @param configuration the configuration file.
   * @return the rate limit section.
   */
  @Provides
  static RateLimitConfig rateLimitConfig(final ConfigServerConfiguration configuration) {
    return configuration.getRateLimit();
  }

  /**
   * The token verifier for the configured issuers.
   *
   * @param configuration the configuration file.
   * @return the verifier.
   */
  @Provides
  @Singleton
  static TokenVerifier tokenVerifier(final ConfigServerConfiguration configuration) {
    return new TokenVerifier(configuration.getAuth(), configuration.getCache().getRetryAfter());
  }
}
