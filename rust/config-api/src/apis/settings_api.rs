/*
 * Config Service API
 *
 * Read the configuration settings for one application in one environment. This file is the contract for every client: the Java `config-client` module and the Rust `config-client` crate are both written (or generated) against it.  Standards: HTTP semantics, conditional requests and status codes from RFC 9110; caching from RFC 9111 and RFC 5861; `429 Too Many Requests` from RFC 6585; JSON bodies per RFC 8259; bearer tokens from RFC 6750, carrying a JWT (RFC 7519) from an OpenID Connect issuer.  The API is read-only. No client can change configuration; changes are made only in-process, by Java code using the `config-datastore` library.  A settings set is a flat map of property keys (namespaced with `.`, such as `database.url`) to string values, plus a version. The version is a positive integer that increases on every change, so a client that remembers it can tell when the settings have changed. Clients check the version with `HEAD` (or a conditional `GET`) and fetch the values only when it differs.  A server caches versions briefly (about a second), and a deployment can run several servers, so a version check can report a version older than one the client already has. A client only ever moves forward: it adopts a version higher than the one it holds and ignores a lower one.  Only `GET` and `HEAD` exist; any other method is `405 Method Not Allowed` with an `Allow` header. Responses are JSON; an `Accept` header that excludes `application/json` is `406 Not Acceptable`. Every error, including a path the server cannot parse (`400`), has the `Error` body.  Authentication and access:  - Every request carries `Authorization: Bearer <JWT>` (see the `bearerAuth` security scheme).   The token comes from an OpenID Connect issuer the server is configured to trust; the   server only checks tokens, it never issues them. - The token says who the client is (`iss` and `sub`). The server keeps, for each application   and environment, the list of subjects allowed to read its settings. Access is granted there,   not by claims in the token. - The server checks in this order: the token (`401`), the names in the path (`400`), access   to that application and environment (`403`), then whether the settings exist (`404`). A   client without access gets `403` whether or not the settings exist, so a token cannot be   used to discover which applications and environments exist. - Tokens should be short-lived (minutes to an hour). Clients get a new token before the old   one expires; the issuer, not this API, defines how. - Each token may make a limited number of requests (a burst, then a steady rate; by default   20, then 60 a minute). Over the limit is `429` with `Retry-After`. The limit is per token,   not per identity, so many instances of one service, each with its own token, are not   limited together.  How often to check:  - Every settings response carries   `Cache-Control: private, max-age=<s>, stale-while-revalidate=<s>, stale-if-error=<s>`   (RFC 9111 and RFC 5861). The server sets `max-age` per application and environment   (30 seconds by default), so it can slow clients down without changing them. - A client uses its values for `max-age` seconds without asking again, then checks the   version. A `304 Not Modified` starts a new `max-age` period. - Lookups read the client's in-memory values; they never wait on the network. Checking the   version happens in the background. - `stale-while-revalidate` (the same as `max-age`): once `max-age` has passed, the client's   values are stale. For up to this long it keeps serving them while the check runs. If no   check has succeeded by then, the values are treated as failing to revalidate: the   `stale-if-error` rules below apply. - `stale-if-error` (1 day by default): while the service answers `500`, `502`, `503` or `504`,   or cannot be reached, a client keeps using its values for this long without treating it as   a problem. Past it, a client still keeps its values (dropping a running service's   configuration is worse than old values) but reports them as stale, for example in logs or   a health check. - A client adds random jitter (up to 10% of `max-age`) to each wait, so instances started   together do not poll together. - After `429 Too Many Requests` or `503 Service Unavailable`, a client waits at least   `Retry-After` seconds before its next request, and keeps using the values it has. - A client that already has values keeps them on every error, and reports the problem (logs,   a health check) rather than dropping configuration a running service depends on:   - `401`: its token expired or was refused. It gets a new token and checks again.   - `403`: its access was revoked. It keeps checking every `max-age`, so access granted again     is picked up.   - `404`: the settings were deleted. It keeps checking every `max-age`.   A client with no values yet has nothing to keep: it fails or waits, as its application   decides. - `private` means shared caches (proxies, CDNs) must not store responses: settings can hold   secrets. 
 *
 * The version of the OpenAPI document: 0.1.0
 * 
 * Generated by: https://openapi-generator.tech
 */


use reqwest;
use serde::{Deserialize, Serialize, de::Error as _};
use crate::{apis::ResponseContent, models};
use super::{Error, configuration, ContentType};


/// struct for typed successes of method [`get_setting`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingSuccess {
    Status200(models::Setting),
    UnknownValue(serde_json::Value),
}

/// struct for typed successes of method [`get_settings`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingsSuccess {
    Status200(models::Settings),
    Status304(),
    UnknownValue(serde_json::Value),
}

/// struct for typed successes of method [`get_settings_version`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingsVersionSuccess {
    Status200(),
    Status304(),
    UnknownValue(serde_json::Value),
}

/// struct for typed errors of method [`get_setting`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingError {
    Status400(models::ErrorMessage),
    Status401(models::ErrorMessage),
    Status403(models::ErrorMessage),
    Status404(models::ErrorMessage),
    Status406(models::ErrorMessage),
    Status429(models::ErrorMessage),
    Status500(models::ErrorMessage),
    Status503(models::ErrorMessage),
    UnknownValue(serde_json::Value),
}

/// struct for typed errors of method [`get_settings`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingsError {
    Status400(models::ErrorMessage),
    Status401(models::ErrorMessage),
    Status403(models::ErrorMessage),
    Status404(models::ErrorMessage),
    Status406(models::ErrorMessage),
    Status429(models::ErrorMessage),
    Status500(models::ErrorMessage),
    Status503(models::ErrorMessage),
    UnknownValue(serde_json::Value),
}

/// struct for typed errors of method [`get_settings_version`]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum GetSettingsVersionError {
    Status400(),
    Status401(),
    Status403(),
    Status404(),
    Status406(),
    Status429(),
    Status500(),
    Status503(),
    UnknownValue(serde_json::Value),
}


/// Returns one key's value. The `ETag` and `Config-Version` headers carry the version of the whole set the value was read from. Clients that read more than one key should get the whole set instead.  Conditional requests are not supported here: `If-None-Match` is ignored, and the answer is always the value. `HEAD` is accepted and returns the same status and headers without the body. 
pub async fn get_setting(configuration: &configuration::Configuration, application: &str, environment: &str, key: &str) -> Result<ResponseContent<GetSettingSuccess>, Error<GetSettingError>> {
    // add a prefix to parameters to efficiently prevent name collisions
    let p_path_application = application;
    let p_path_environment = environment;
    let p_path_key = key;

    let uri_str = format!("{}/v1/applications/{application}/environments/{environment}/settings/{key}", configuration.base_path, application=crate::apis::urlencode(p_path_application), environment=crate::apis::urlencode(p_path_environment), key=crate::apis::urlencode(p_path_key));
    let mut req_builder = configuration.client.request(reqwest::Method::GET, &uri_str);

    if let Some(ref user_agent) = configuration.user_agent {
        req_builder = req_builder.header(reqwest::header::USER_AGENT, user_agent.clone());
    }
    if let Some(ref token) = configuration.bearer_access_token {
        req_builder = req_builder.bearer_auth(token.to_owned());
    };

    let req = req_builder.build()?;
    let resp = configuration.client.execute(req).await?;

    let status = resp.status();
    let headers = resp.headers().clone();

    if !status.is_client_error() && !status.is_server_error() {
        let content = resp.text().await?;
        let entity: Option<GetSettingSuccess> = serde_json::from_str(&content).ok();
        Ok(ResponseContent { status, headers, content, entity })
    } else {
        let content = resp.text().await?;
        let entity: Option<GetSettingError> = serde_json::from_str(&content).ok();
        Err(Error::ResponseError(ResponseContent { status, headers, content, entity }))
    }
}

/// Returns every key and value with the set's version. Send the `ETag` from an earlier response in `If-None-Match` to get `304 Not Modified` when nothing has changed.  Every request carries the client's token (shortened in these examples).  First request:  ```http GET /v1/applications/billing/environments/prod/settings Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ...  HTTP/1.1 200 OK ETag: \"42\" Config-Version: 42 Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400 Content-Type: application/json  {\"application\":\"billing\",\"environment\":\"prod\",\"version\":42,  \"values\":{\"database.url\":\"jdbc:postgresql://db:5432/billing\",\"database.pool.size\":\"10\"}} ```  Later, with the version the client has:  ```http GET /v1/applications/billing/environments/prod/settings Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ... If-None-Match: \"42\"  HTTP/1.1 304 Not Modified ETag: \"42\" Config-Version: 42 Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400 ```  If the settings changed, the same request gets `200 OK` with the new values and `ETag: \"43\"` instead. 
pub async fn get_settings(configuration: &configuration::Configuration, application: &str, environment: &str, if_none_match: Option<&str>) -> Result<ResponseContent<GetSettingsSuccess>, Error<GetSettingsError>> {
    // add a prefix to parameters to efficiently prevent name collisions
    let p_path_application = application;
    let p_path_environment = environment;
    let p_header_if_none_match = if_none_match;

    let uri_str = format!("{}/v1/applications/{application}/environments/{environment}/settings", configuration.base_path, application=crate::apis::urlencode(p_path_application), environment=crate::apis::urlencode(p_path_environment));
    let mut req_builder = configuration.client.request(reqwest::Method::GET, &uri_str);

    if let Some(ref user_agent) = configuration.user_agent {
        req_builder = req_builder.header(reqwest::header::USER_AGENT, user_agent.clone());
    }
    if let Some(param_value) = p_header_if_none_match {
        req_builder = req_builder.header("If-None-Match", param_value.to_string());
    }
    if let Some(ref token) = configuration.bearer_access_token {
        req_builder = req_builder.bearer_auth(token.to_owned());
    };

    let req = req_builder.build()?;
    let resp = configuration.client.execute(req).await?;

    let status = resp.status();
    let headers = resp.headers().clone();

    if !status.is_client_error() && !status.is_server_error() {
        let content = resp.text().await?;
        let entity: Option<GetSettingsSuccess> = serde_json::from_str(&content).ok();
        Ok(ResponseContent { status, headers, content, entity })
    } else {
        let content = resp.text().await?;
        let entity: Option<GetSettingsError> = serde_json::from_str(&content).ok();
        Err(Error::ResponseError(ResponseContent { status, headers, content, entity }))
    }
}

/// The same status and version headers as `GET`, without a body: the version is in the `Config-Version` and `ETag` headers. Use it to poll for changes cheaply. It is answered from the version alone, so it has no `Content-Length` (RFC 9110, section 8.6).  Without a version, to learn the current one:  ```http HEAD /v1/applications/billing/environments/prod/settings Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ...  HTTP/1.1 200 OK ETag: \"42\" Config-Version: 42 Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400 ```  With the version the client has, when nothing has changed:  ```http HEAD /v1/applications/billing/environments/prod/settings Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ... If-None-Match: \"42\"  HTTP/1.1 304 Not Modified ETag: \"42\" Config-Version: 42 Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400 ```  With the version the client has, after the settings changed:  ```http HEAD /v1/applications/billing/environments/prod/settings Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ... If-None-Match: \"42\"  HTTP/1.1 200 OK ETag: \"43\" Config-Version: 43 Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400 ```  The version is now 43, so the client gets the new values with `GET`. 
pub async fn get_settings_version(configuration: &configuration::Configuration, application: &str, environment: &str, if_none_match: Option<&str>) -> Result<ResponseContent<GetSettingsVersionSuccess>, Error<GetSettingsVersionError>> {
    // add a prefix to parameters to efficiently prevent name collisions
    let p_path_application = application;
    let p_path_environment = environment;
    let p_header_if_none_match = if_none_match;

    let uri_str = format!("{}/v1/applications/{application}/environments/{environment}/settings", configuration.base_path, application=crate::apis::urlencode(p_path_application), environment=crate::apis::urlencode(p_path_environment));
    let mut req_builder = configuration.client.request(reqwest::Method::HEAD, &uri_str);

    if let Some(ref user_agent) = configuration.user_agent {
        req_builder = req_builder.header(reqwest::header::USER_AGENT, user_agent.clone());
    }
    if let Some(param_value) = p_header_if_none_match {
        req_builder = req_builder.header("If-None-Match", param_value.to_string());
    }
    if let Some(ref token) = configuration.bearer_access_token {
        req_builder = req_builder.bearer_auth(token.to_owned());
    };

    let req = req_builder.build()?;
    let resp = configuration.client.execute(req).await?;

    let status = resp.status();
    let headers = resp.headers().clone();

    if !status.is_client_error() && !status.is_server_error() {
        let content = resp.text().await?;
        let entity: Option<GetSettingsVersionSuccess> = serde_json::from_str(&content).ok();
        Ok(ResponseContent { status, headers, content, entity })
    } else {
        let content = resp.text().await?;
        let entity: Option<GetSettingsVersionError> = serde_json::from_str(&content).ok();
        Err(Error::ResponseError(ResponseContent { status, headers, content, entity }))
    }
}

