/*
 * Config Service API
 *
 * Read the configuration settings for one application in one environment. This file is the contract for every client: the Java `config-client` module and the Rust `config-client` crate are both written (or generated) against it.  Standards: HTTP semantics, conditional requests and status codes from RFC 9110; caching from RFC 9111 and RFC 5861; `429 Too Many Requests` from RFC 6585; JSON bodies per RFC 8259; bearer tokens from RFC 6750, carrying a JWT (RFC 7519) from an OpenID Connect issuer.  The API is read-only. No client can change configuration; changes are made only in-process, by Java code using the `config-datastore` library.  A settings set is a flat map of property keys (namespaced with `.`, such as `database.url`) to string values, plus a version. The version is a positive integer that increases on every change, so a client that remembers it can tell when the settings have changed. Clients check the version with `HEAD` (or a conditional `GET`) and fetch the values only when it differs.  A server caches versions briefly (about a second), and a deployment can run several servers, so a version check can report a version older than one the client already has. A client only ever moves forward: it adopts a version higher than the one it holds and ignores a lower one.  Only `GET` and `HEAD` exist; any other method is `405 Method Not Allowed` with an `Allow` header. Responses are JSON; an `Accept` header that excludes `application/json` is `406 Not Acceptable`. Every error, including a path the server cannot parse (`400`), has the `Error` body.  Authentication and access:  - Every request carries `Authorization: Bearer <JWT>` (see the `bearerAuth` security scheme).   The token comes from an OpenID Connect issuer the server is configured to trust; the   server only checks tokens, it never issues them. - The token says who the client is (`iss` and `sub`). The server keeps, for each application   and environment, the list of subjects allowed to read its settings. Access is granted there,   not by claims in the token. - The server checks in this order: the token (`401`), the names in the path (`400`), access   to that application and environment (`403`), then whether the settings exist (`404`). A   client without access gets `403` whether or not the settings exist, so a token cannot be   used to discover which applications and environments exist. - Tokens should be short-lived (minutes to an hour). Clients get a new token before the old   one expires; the issuer, not this API, defines how. - Each token may make a limited number of requests (a burst, then a steady rate; by default   20, then 60 a minute). Over the limit is `429` with `Retry-After`. The limit is per token,   not per identity, so many instances of one service, each with its own token, are not   limited together.  How often to check:  - Every settings response carries   `Cache-Control: private, max-age=<s>, stale-while-revalidate=<s>, stale-if-error=<s>`   (RFC 9111 and RFC 5861). The server sets `max-age` per application and environment   (30 seconds by default), so it can slow clients down without changing them. - A client uses its values for `max-age` seconds without asking again, then checks the   version. A `304 Not Modified` starts a new `max-age` period. - Lookups read the client's in-memory values; they never wait on the network. Checking the   version happens in the background. - `stale-while-revalidate` (the same as `max-age`): once `max-age` has passed, the client's   values are stale. For up to this long it keeps serving them while the check runs. If no   check has succeeded by then, the values are treated as failing to revalidate: the   `stale-if-error` rules below apply. - `stale-if-error` (1 day by default): while the service answers `500`, `502`, `503` or `504`,   or cannot be reached, a client keeps using its values for this long without treating it as   a problem. Past it, a client still keeps its values (dropping a running service's   configuration is worse than old values) but reports them as stale, for example in logs or   a health check. - A client adds random jitter (up to 10% of `max-age`) to each wait, so instances started   together do not poll together. - After `429 Too Many Requests` or `503 Service Unavailable`, a client waits at least   `Retry-After` seconds before its next request, and keeps using the values it has. - A client that already has values keeps them on every error, and reports the problem (logs,   a health check) rather than dropping configuration a running service depends on:   - `401`: its token expired or was refused. It gets a new token and checks again.   - `403`: its access was revoked. It keeps checking every `max-age`, so access granted again     is picked up.   - `404`: the settings were deleted. It keeps checking every `max-age`.   A client with no values yet has nothing to keep: it fails or waits, as its application   decides. - `private` means shared caches (proxies, CDNs) must not store responses: settings can hold   secrets. 
 *
 * The version of the OpenAPI document: 0.1.0
 * 
 * Generated by: https://openapi-generator.tech
 */


pub use reqwest::Client;

#[derive(Clone)]
pub struct Configuration {
    pub base_path: String,
    pub user_agent: Option<String>,
    pub client: reqwest::Client,
    pub basic_auth: Option<BasicAuth>,
    pub oauth_access_token: Option<String>,
    pub bearer_access_token: Option<String>,
    pub api_key: Option<ApiKey>,
}

pub type BasicAuth = (String, Option<String>);

#[derive(Clone)]
pub struct ApiKey {
    pub prefix: Option<String>,
    pub key: String,
}

impl std::fmt::Debug for ApiKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ApiKey")
            .field("prefix", &self.prefix)
            .field("key", &"[REDACTED]")
            .finish()
    }
}


impl std::fmt::Debug for Configuration {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut debug = f.debug_struct("Configuration");
        debug
            .field("base_path", &self.base_path)
            .field("user_agent", &self.user_agent)
            .field("client", &self.client);
        debug
            .field("basic_auth", &self.basic_auth.as_ref().map(|(username, password)| (username, password.as_ref().map(|_| "[REDACTED]"))))
            .field("oauth_access_token", &self.oauth_access_token.as_ref().map(|_| "[REDACTED]"))
            .field("bearer_access_token", &self.bearer_access_token.as_ref().map(|_| "[REDACTED]"))
            .field("api_key", &self.api_key);
        debug.finish()
    }
}

impl Configuration {
    pub fn new() -> Configuration {
        Configuration::default()
    }
}

impl Default for Configuration {
    fn default() -> Self {
        Configuration {
            base_path: "http://localhost:8080".to_owned(),
            user_agent: Some("OpenAPI-Generator/0.1.0/rust".to_owned()),
            client: reqwest::Client::new(),
            basic_auth: None,
            oauth_access_token: None,
            bearer_access_token: None,
            api_key: None,
        }
    }
}
