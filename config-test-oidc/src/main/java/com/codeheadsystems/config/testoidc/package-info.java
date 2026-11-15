/**
 * An in-process OpenID Connect issuer for tests: serves a discovery document and JWKS on the
 * loopback interface and signs JWT access tokens with any claims, including deliberately invalid
 * ones. See {@link com.codeheadsystems.config.testoidc.TestOidcIssuer}. Not published.
 */
package com.codeheadsystems.config.testoidc;
