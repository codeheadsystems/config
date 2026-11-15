//! [`ConfigClient`]: an application's settings from the config service, following the client
//! rules in `openapi/config-api.yaml`.

use std::collections::HashMap;
use std::fmt;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant, SystemTime};

use config_api::apis::Error as ApiError;
use config_api::apis::configuration::Configuration as ApiConfiguration;
use config_api::apis::settings_api::{self, GetSettingsSuccess};
use config_model::AppEnvironment;
use log::{debug, info, warn};
use reqwest::header::HeaderMap;

/// The shortest wait between checks, whatever `max-age` says.
pub const MINIMUM_INTERVAL: Duration = Duration::from_secs(1);
/// Connect and request timeout when none is set.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);

const DEFAULT_MAX_AGE: Duration = Duration::from_secs(30);
const DEFAULT_STALE_IF_ERROR: Duration = Duration::from_secs(86_400);
const MAX_JITTER: f64 = 0.1;

/// Where the bearer token comes from. It is read before every request, so a token the platform
/// renews (such as a Kubernetes projected service account token) is always current.
#[derive(Clone)]
pub enum TokenSource {
    /// A fixed token.
    Static(String),
    /// A file holding the token, read before every request.
    File(PathBuf),
    /// A function returning the current token; it must be cheap.
    Function(Arc<dyn Fn() -> Result<String, String> + Send + Sync>),
}

impl TokenSource {
    fn token(&self) -> Result<String, String> {
        match self {
            TokenSource::Static(token) => Ok(token.clone()),
            TokenSource::File(path) => std::fs::read_to_string(path)
                .map(|token| token.trim().to_string())
                .map_err(|e| format!("cannot read the token file {}: {e}", path.display())),
            TokenSource::Function(function) => function(),
        }
    }
}

impl fmt::Debug for TokenSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TokenSource::Static(_) => f.write_str("Static([REDACTED])"),
            TokenSource::File(path) => write!(f, "File({})", path.display()),
            TokenSource::Function(_) => f.write_str("Function(..)"),
        }
    }
}

/// Why a [`ConfigClient`] could not be built.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigClientError {
    /// The builder is missing a setting, or a setting is not valid.
    Builder(String),
    /// [`ConfigClientBuilder::require_settings_at_startup`] is set and the settings could not be read.
    Startup(String),
}

impl fmt::Display for ConfigClientError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConfigClientError::Builder(message) | ConfigClientError::Startup(message) => {
                f.write_str(message)
            }
        }
    }
}

impl std::error::Error for ConfigClientError {}

/// The `Cache-Control` the server sends with settings (RFC 9111, RFC 5861).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct CachePolicy {
    pub(crate) max_age: Duration,
    pub(crate) stale_while_revalidate: Duration,
    pub(crate) stale_if_error: Duration,
}

impl CachePolicy {
    pub(crate) const DEFAULT: CachePolicy = CachePolicy {
        max_age: DEFAULT_MAX_AGE,
        stale_while_revalidate: DEFAULT_MAX_AGE,
        stale_if_error: DEFAULT_STALE_IF_ERROR,
    };

    /// Parses the header; missing or malformed directives use the spec's defaults.
    pub(crate) fn parse(header: Option<&str>) -> CachePolicy {
        let Some(header) = header else {
            return CachePolicy::DEFAULT;
        };
        let (mut max_age, mut swr, mut sie) = (None, None, None);
        for directive in header.split(',') {
            let Some((name, value)) = directive.trim().split_once('=') else {
                continue;
            };
            let seconds = value
                .trim()
                .trim_matches('"')
                .parse::<u64>()
                .ok()
                .map(Duration::from_secs);
            match name.trim().to_ascii_lowercase().as_str() {
                "max-age" => max_age = seconds,
                "stale-while-revalidate" => swr = seconds,
                "stale-if-error" => sie = seconds,
                _ => {}
            }
        }
        let max_age = max_age.unwrap_or(DEFAULT_MAX_AGE);
        CachePolicy {
            max_age,
            stale_while_revalidate: swr.unwrap_or(max_age),
            stale_if_error: sie.unwrap_or(DEFAULT_STALE_IF_ERROR),
        }
    }
}

/// What a check found, before it is applied to the state.
enum Failure {
    Token(String),
    Network(String),
    Response {
        status: u16,
        headers: HeaderMap,
        message: String,
    },
}

impl Failure {
    fn describe(&self) -> String {
        match self {
            Failure::Token(why) => format!("cannot get a bearer token: {why}"),
            Failure::Network(why) => format!("cannot reach the config service: {why}"),
            Failure::Response {
                status, message, ..
            } => format!("{status}: {message}"),
        }
    }
}

struct State {
    values: Arc<HashMap<String, String>>,
    version: i64, // 0 until settings are received
    policy: CachePolicy,
    last_success: Option<(Instant, SystemTime)>,
    problem: Option<String>,
    failing: bool,
    reported_stale: bool,
    next_check: Duration,
}

struct Inner {
    target: AppEnvironment,
    api: ApiConfiguration,
    tokens: TokenSource,
    defaults: HashMap<String, String>,
    jitter: fn() -> f64,
    state: RwLock<State>,
    checking: tokio::sync::Mutex<()>,
}

/// An application's settings from the config service.
///
/// - **Lookups are local**: [`get`](Self::get) reads the last settings received, so it is cheap and
///   never waits on, or fails because of, the network.
/// - **Polling follows the server**: [`spawn_refresh`](Self::spawn_refresh) checks again after the
///   response's `max-age` (at least one second) plus up to 10% jitter. Each check is a conditional
///   `GET`: `304 Not Modified` when nothing changed. After `429` or `503` it waits at least
///   `Retry-After`.
/// - **Versions only move forward**: an older version (from a server whose cache lags) is ignored.
/// - **Errors never drop values**: on any failure the last settings stay in use, the problem is
///   logged when it starts and reported by [`problem`](Self::problem), and checking continues. On
///   `401` the request is retried once at once if the token has changed since.
///
/// ```no_run
/// # async fn example() -> Result<(), config_client::ConfigClientError> {
/// use config_client::{AppEnvironment, ConfigClient};
///
/// let client = ConfigClient::builder(AppEnvironment::new("billing", "prod"))
///     .base_url("https://config.example.com")
///     .token_file("/var/run/secrets/tokens/config-token")
///     .build()
///     .await?;
/// let _refresh = client.spawn_refresh(); // checks in the background until dropped
/// let url = client.get("database.url");
/// # Ok(())
/// # }
/// ```
///
/// Cloning is cheap; clones share the same settings.
#[derive(Clone)]
pub struct ConfigClient {
    inner: Arc<Inner>,
}

impl fmt::Debug for ConfigClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ConfigClient")
            .field("target", &self.inner.target)
            .field("base_url", &self.inner.api.base_path)
            .field("version", &self.version())
            .finish()
    }
}

impl ConfigClient {
    /// Starts building a client for one application in one environment.
    pub fn builder(target: AppEnvironment) -> ConfigClientBuilder {
        ConfigClientBuilder {
            target,
            base_url: None,
            tokens: None,
            defaults: HashMap::new(),
            timeout: DEFAULT_TIMEOUT,
            require_settings_at_startup: false,
            jitter: fastrand::f64,
        }
    }

    /// A value: from the settings, else from the defaults.
    pub fn get(&self, key: &str) -> Option<String> {
        let values = self.read().values.clone();
        values
            .get(key)
            .or_else(|| self.inner.defaults.get(key))
            .cloned()
    }

    /// The settings received from the config service, without the defaults.
    pub fn settings(&self) -> Arc<HashMap<String, String>> {
        self.read().values.clone()
    }

    /// The version of the settings being served, or 0 if none have been received.
    pub fn version(&self) -> i64 {
        self.read().version
    }

    /// Whether settings have been received at least once.
    pub fn settings_loaded(&self) -> bool {
        self.read().version > 0
    }

    /// When a check last succeeded (settings received, or confirmed unchanged).
    pub fn last_checked(&self) -> Option<SystemTime> {
        self.read().last_success.map(|(_, at)| at)
    }

    /// Why the last check failed, such as `"403: Not allowed to read application billing in
    /// environment prod"`; `None` if it succeeded.
    pub fn problem(&self) -> Option<String> {
        self.read().problem.clone()
    }

    /// Whether the settings are stale: no check has succeeded for `max-age` plus `stale-if-error`
    /// (one day by default), or none ever has. Stale settings are still served.
    pub fn stale(&self) -> bool {
        let state = self.read();
        match state.last_success {
            None => true,
            Some((at, _)) => at.elapsed() > state.policy.max_age + state.policy.stale_if_error,
        }
    }

    /// Checks the config service now. Never fails: a failure is logged, recorded in
    /// [`problem`](Self::problem), and the current settings kept.
    ///
    /// Returns true if new settings were loaded.
    pub async fn refresh(&self) -> bool {
        self.check().await.0
    }

    /// Checks in the background, on the schedule the server sets, until the returned task is
    /// dropped or stopped. Needs a Tokio runtime.
    pub fn spawn_refresh(&self) -> RefreshTask {
        let client = self.clone();
        let first = self.next_check();
        RefreshTask {
            handle: tokio::spawn(async move {
                let mut delay = first;
                loop {
                    tokio::time::sleep(delay).await;
                    delay = client.check().await.1;
                }
            }),
        }
    }

    pub(crate) fn next_check(&self) -> Duration {
        self.read().next_check
    }

    fn read(&self) -> std::sync::RwLockReadGuard<'_, State> {
        self.inner
            .state
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn write(&self) -> std::sync::RwLockWriteGuard<'_, State> {
        self.inner
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn describe(&self) -> String {
        format!(
            "{}/{}",
            self.inner.target.application, self.inner.target.environment
        )
    }

    /// One check: returns whether settings changed, and the wait before the next check.
    async fn check(&self) -> (bool, Duration) {
        let _one_at_a_time = self.inner.checking.lock().await;
        let held = self.version();
        let if_none_match = (held > 0).then(|| format!("\"{held}\""));
        let outcome = match self.get_settings(if_none_match.as_deref()).await {
            Ok((status, headers, settings)) => self.succeeded(held, status, &headers, settings),
            Err(failure) => self.failed(failure),
        };
        self.write().next_check = outcome.1;
        outcome
    }

    async fn get_settings(
        &self,
        if_none_match: Option<&str>,
    ) -> Result<(u16, HeaderMap, Option<config_api::models::Settings>), Failure> {
        let token = self.inner.tokens.token().map_err(Failure::Token)?;
        match self.call(&token, if_none_match).await {
            Err(Failure::Response { status: 401, .. })
                if self.inner.tokens.token().is_ok_and(|fresh| fresh != token) =>
            {
                debug!("Token refused, and a new one is available; retrying");
                let fresh = self.inner.tokens.token().map_err(Failure::Token)?;
                self.call(&fresh, if_none_match).await
            }
            other => other,
        }
    }

    async fn call(
        &self,
        token: &str,
        if_none_match: Option<&str>,
    ) -> Result<(u16, HeaderMap, Option<config_api::models::Settings>), Failure> {
        let mut api = self.inner.api.clone();
        api.bearer_access_token = Some(token.to_string());
        let target = &self.inner.target;
        match settings_api::get_settings(
            &api,
            &target.application,
            &target.environment,
            if_none_match,
        )
        .await
        {
            Ok(response) => {
                let settings = match response.entity {
                    Some(GetSettingsSuccess::Status200(settings)) => Some(settings),
                    _ => None,
                };
                Ok((response.status.as_u16(), response.headers, settings))
            }
            Err(ApiError::ResponseError(response)) => Err(Failure::Response {
                status: response.status.as_u16(),
                headers: response.headers,
                message: serde_json::from_str::<serde_json::Value>(&response.content)
                    .ok()
                    .and_then(|body| {
                        body.get("message")
                            .and_then(|m| m.as_str())
                            .map(str::to_string)
                    })
                    .unwrap_or(response.content),
            }),
            Err(ApiError::Reqwest(e)) => Err(Failure::Network(e.to_string())),
            Err(ApiError::Io(e)) => Err(Failure::Network(e.to_string())),
            Err(ApiError::Serde(e)) => Err(Failure::Network(format!("unreadable response: {e}"))),
        }
    }

    fn succeeded(
        &self,
        held: i64,
        status: u16,
        headers: &HeaderMap,
        settings: Option<config_api::models::Settings>,
    ) -> (bool, Duration) {
        let policy = CachePolicy::parse(header(headers, "cache-control"));
        let next = self.interval(policy.max_age);
        let settings = match (status, settings) {
            (304, _) => None,
            (200, Some(settings)) => Some(settings),
            _ => {
                let failure = Failure::Response {
                    status,
                    headers: headers.clone(),
                    message: "unexpected response".to_string(),
                };
                return self.failed(failure);
            }
        };
        let mut state = self.write();
        state.policy = policy;
        state.last_success = Some((Instant::now(), SystemTime::now()));
        state.problem = None;
        state.reported_stale = false;
        if state.failing {
            state.failing = false;
            info!("Config service reachable again for {}", self.describe());
        }
        let Some(settings) = settings else {
            return (false, next);
        };
        if held > 0 && settings.version <= held {
            if settings.version < held {
                debug!(
                    "Ignoring {} settings version {}: already serving version {held}",
                    self.describe(),
                    settings.version
                );
            }
            return (false, next);
        }
        info!(
            "Loaded {} settings version {} ({} keys)",
            self.describe(),
            settings.version,
            settings.values.len()
        );
        state.version = settings.version;
        state.values = Arc::new(settings.values);
        (true, next)
    }

    fn failed(&self, failure: Failure) -> (bool, Duration) {
        let why = failure.describe();
        let stale = self.stale();
        let mut state = self.write();
        let normal = self.interval(state.policy.max_age);
        let serving = if state.version > 0 {
            format!("version {}", state.version)
        } else {
            "defaults only".to_string()
        };
        if !state.failing {
            state.failing = true;
            warn!(
                "Cannot check {} settings ({why}); serving {serving}",
                self.describe()
            );
        } else {
            debug!("Still cannot check {} settings: {why}", self.describe());
        }
        if stale && !state.reported_stale && state.version > 0 {
            state.reported_stale = true;
            warn!(
                "{} settings version {} are stale: no successful check within max-age plus stale-if-error",
                self.describe(),
                state.version
            );
        }
        state.problem = Some(why);
        let next = match failure {
            Failure::Response {
                status: 429 | 503,
                headers,
                ..
            } => header(&headers, "retry-after")
                .and_then(|seconds| seconds.trim().parse::<u64>().ok())
                .map(Duration::from_secs)
                .filter(|retry_after| *retry_after > normal)
                .unwrap_or(normal),
            _ => normal,
        };
        (false, next)
    }

    /// The wait before the next check: at least a second, plus up to 10% jitter.
    fn interval(&self, max_age: Duration) -> Duration {
        let base = max_age.max(MINIMUM_INTERVAL);
        base + base.mul_f64(MAX_JITTER * (self.inner.jitter)())
    }
}

fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

/// The background checks started by [`ConfigClient::spawn_refresh`]; they stop when this is
/// dropped or [`stop`](Self::stop)ped. The client keeps serving the last settings received.
#[derive(Debug)]
pub struct RefreshTask {
    handle: tokio::task::JoinHandle<()>,
}

impl RefreshTask {
    /// Stops the background checks.
    pub fn stop(self) {}
}

impl Drop for RefreshTask {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

/// Builds a [`ConfigClient`]. Set the [`base_url`](Self::base_url) and a token.
#[derive(Debug)]
pub struct ConfigClientBuilder {
    target: AppEnvironment,
    base_url: Option<String>,
    tokens: Option<TokenSource>,
    defaults: HashMap<String, String>,
    timeout: Duration,
    require_settings_at_startup: bool,
    jitter: fn() -> f64,
}

impl ConfigClientBuilder {
    /// The config service, such as `https://config.example.com`. Use `https`: the bearer token
    /// must only be sent over TLS.
    pub fn base_url(mut self, base_url: impl Into<String>) -> Self {
        self.base_url = Some(base_url.into());
        self
    }

    /// A fixed bearer token.
    pub fn token(self, token: impl Into<String>) -> Self {
        self.token_source(TokenSource::Static(token.into()))
    }

    /// A file holding the bearer token, read before every request.
    pub fn token_file(self, path: impl Into<PathBuf>) -> Self {
        self.token_source(TokenSource::File(path.into()))
    }

    /// A function returning the current bearer token, called before every request.
    pub fn token_fn(
        self,
        function: impl Fn() -> Result<String, String> + Send + Sync + 'static,
    ) -> Self {
        self.token_source(TokenSource::Function(Arc::new(function)))
    }

    /// Where the bearer token comes from.
    pub fn token_source(mut self, tokens: TokenSource) -> Self {
        self.tokens = Some(tokens);
        self
    }

    /// Values for keys the settings do not have, and for every key until settings are first received.
    pub fn defaults(mut self, defaults: HashMap<String, String>) -> Self {
        self.defaults = defaults;
        self
    }

    /// Connect and request timeout; [`DEFAULT_TIMEOUT`] if not set.
    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    /// Whether [`build`](Self::build) fails when the settings cannot be read. By default (false)
    /// the client starts with the defaults and keeps trying.
    pub fn require_settings_at_startup(mut self, require: bool) -> Self {
        self.require_settings_at_startup = require;
        self
    }

    #[cfg(test)]
    pub(crate) fn jitter(mut self, jitter: fn() -> f64) -> Self {
        self.jitter = jitter;
        self
    }

    /// Makes the first check and returns the client.
    pub async fn build(self) -> Result<ConfigClient, ConfigClientError> {
        let base_url = self
            .base_url
            .ok_or_else(|| ConfigClientError::Builder("no base_url set".to_string()))?;
        let tokens = self.tokens.ok_or_else(|| {
            ConfigClientError::Builder(
                "no token set: set token, token_file or token_fn".to_string(),
            )
        })?;
        if self.timeout.is_zero() {
            return Err(ConfigClientError::Builder(
                "timeout must be positive".to_string(),
            ));
        }
        let http = reqwest::Client::builder()
            .connect_timeout(self.timeout)
            .timeout(self.timeout)
            .build()
            .map_err(|e| {
                ConfigClientError::Builder(format!("cannot build the HTTP client: {e}"))
            })?;
        let mut api = ApiConfiguration::new();
        api.base_path = base_url.trim_end_matches('/').to_string();
        api.client = http;
        let client = ConfigClient {
            inner: Arc::new(Inner {
                target: self.target,
                api,
                tokens,
                defaults: self.defaults,
                jitter: self.jitter,
                state: RwLock::new(State {
                    values: Arc::new(HashMap::new()),
                    version: 0,
                    policy: CachePolicy::DEFAULT,
                    last_success: None,
                    problem: None,
                    failing: false,
                    reported_stale: false,
                    next_check: DEFAULT_MAX_AGE,
                }),
                checking: tokio::sync::Mutex::new(()),
            }),
        };
        client.check().await;
        if self.require_settings_at_startup && !client.settings_loaded() {
            return Err(ConfigClientError::Startup(format!(
                "Cannot read {} settings from the config service: {}",
                client.describe(),
                client.problem().unwrap_or_default()
            )));
        }
        Ok(client)
    }
}

#[cfg(test)]
mod tests;
