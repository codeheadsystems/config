//! ConfigClient against a scripted config service on a local port.

use super::*;
use std::collections::VecDeque;
use std::sync::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const CACHE_CONTROL: &str = "private, max-age=30, stale-while-revalidate=30, stale-if-error=86400";

/// A request as received, lowercased.
#[derive(Debug, Clone)]
struct Request(String);

impl Request {
    fn line(&self) -> &str {
        self.0.lines().next().unwrap_or_default()
    }

    fn header(&self, name: &str) -> Option<String> {
        let prefix = format!("{}: ", name.to_ascii_lowercase());
        self.0
            .lines()
            .find_map(|line| line.strip_prefix(&prefix).map(str::to_string))
    }
}

/// Answers each connection with the next scripted response (500 when none is left).
struct FakeService {
    url: String,
    responses: Arc<Mutex<VecDeque<String>>>,
    requests: Arc<Mutex<Vec<Request>>>,
    task: tokio::task::JoinHandle<()>,
}

impl FakeService {
    async fn start() -> FakeService {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let responses = Arc::new(Mutex::new(VecDeque::new()));
        let requests = Arc::new(Mutex::new(Vec::new()));
        let (scripted, seen) = (responses.clone(), requests.clone());
        let task = tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else {
                    return;
                };
                let mut buffer = vec![0u8; 16 * 1024];
                let read = socket.read(&mut buffer).await.unwrap_or(0);
                seen.lock().unwrap().push(Request(
                    String::from_utf8_lossy(&buffer[..read]).to_lowercase(),
                ));
                let response = scripted.lock().unwrap().pop_front().unwrap_or_else(|| {
                    response(
                        "500 Internal Server Error",
                        &[],
                        r#"{"message":"Nothing scripted"}"#,
                    )
                });
                let _ = socket.write_all(response.as_bytes()).await;
                let _ = socket.shutdown().await;
            }
        });
        FakeService {
            url,
            responses,
            requests,
            task,
        }
    }

    fn then(&self, response: String) -> &Self {
        self.responses.lock().unwrap().push_back(response);
        self
    }

    fn settings(&self, version: i64, values: &[(&str, &str)]) -> &Self {
        self.settings_with(version, values, CACHE_CONTROL)
    }

    fn settings_with(&self, version: i64, values: &[(&str, &str)], cache_control: &str) -> &Self {
        let values: HashMap<_, _> = values.iter().copied().collect();
        let body = serde_json::json!({
            "application": "billing", "environment": "prod", "version": version, "values": values
        });
        let etag = format!("\"{version}\"");
        let version = version.to_string();
        self.then(response(
            "200 OK",
            &[
                ("ETag", &etag),
                ("Config-Version", &version),
                ("Cache-Control", cache_control),
            ],
            &body.to_string(),
        ))
    }

    fn not_modified(&self, version: i64) -> &Self {
        let etag = format!("\"{version}\"");
        let version = version.to_string();
        self.then(response(
            "304 Not Modified",
            &[
                ("ETag", &etag),
                ("Config-Version", &version),
                ("Cache-Control", CACHE_CONTROL),
            ],
            "",
        ))
    }

    fn error(&self, status: &str, message: &str, headers: &[(&str, &str)]) -> &Self {
        self.then(response(
            status,
            headers,
            &format!(r#"{{"message":"{message}"}}"#),
        ))
    }

    fn requests(&self) -> Vec<Request> {
        self.requests.lock().unwrap().clone()
    }

    fn builder(&self) -> ConfigClientBuilder {
        ConfigClient::builder(AppEnvironment::new("billing", "prod"))
            .base_url(self.url.clone())
            .token("token-1")
            .jitter(|| 0.0)
    }
}

impl Drop for FakeService {
    fn drop(&mut self) {
        self.task.abort();
    }
}

fn response(status: &str, headers: &[(&str, &str)], body: &str) -> String {
    let mut text = format!("HTTP/1.1 {status}\r\nConnection: close\r\n");
    for (name, value) in headers {
        text.push_str(&format!("{name}: {value}\r\n"));
    }
    if !body.is_empty() {
        text.push_str(&format!(
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            body.len()
        ));
    }
    text.push_str("\r\n");
    text.push_str(body);
    text
}

const V42: &[(&str, &str)] = &[
    ("database.url", "jdbc:postgresql://db/billing"),
    ("pool", "10"),
];

#[tokio::test]
async fn loads_the_settings_at_startup_with_the_token() {
    let service = FakeService::start().await;
    service.settings(42, V42);

    let client = service.builder().build().await.unwrap();

    assert!(client.settings_loaded());
    assert_eq!(client.version(), 42);
    assert_eq!(
        client.get("database.url").as_deref(),
        Some("jdbc:postgresql://db/billing")
    );
    assert_eq!(client.problem(), None);
    assert!(client.last_checked().is_some());
    let request = &service.requests()[0];
    assert_eq!(
        request.line(),
        "get /v1/applications/billing/environments/prod/settings http/1.1"
    );
    assert_eq!(
        request.header("authorization").as_deref(),
        Some("bearer token-1")
    );
    assert_eq!(request.header("if-none-match"), None);
}

#[tokio::test]
async fn later_checks_ask_with_the_version_held_and_304_keeps_the_values() {
    let service = FakeService::start().await;
    service.settings(42, V42).not_modified(42);
    let client = service.builder().build().await.unwrap();

    assert!(!client.refresh().await);

    assert_eq!(
        service.requests()[1].header("if-none-match").as_deref(),
        Some("\"42\"")
    );
    assert_eq!(client.version(), 42);
    assert_eq!(client.get("pool").as_deref(), Some("10"));
}

#[tokio::test]
async fn a_newer_version_replaces_the_values_and_an_older_one_is_ignored() {
    let service = FakeService::start().await;
    service
        .settings(42, V42)
        .settings(43, &[("pool", "20")])
        .settings(41, &[("pool", "1")]);
    let client = service.builder().build().await.unwrap();
    let clone = client.clone();

    assert!(client.refresh().await);
    assert_eq!(clone.version(), 43);
    assert_eq!(clone.get("pool").as_deref(), Some("20"));
    assert_eq!(clone.get("database.url"), None);

    assert!(!client.refresh().await);
    assert_eq!(client.version(), 43);
    assert_eq!(client.problem(), None);
}

#[tokio::test]
async fn every_error_keeps_the_values_and_is_reported_until_a_check_succeeds() {
    let service = FakeService::start().await;
    service
        .settings(42, V42)
        .error("401 Unauthorized", "The access token expired", &[])
        .error(
            "403 Forbidden",
            "Not allowed to read application billing in environment prod",
            &[],
        )
        .error(
            "404 Not Found",
            "No settings for application billing in environment prod",
            &[],
        )
        .error("500 Internal Server Error", "Internal error", &[])
        .not_modified(42);
    let client = service.builder().build().await.unwrap();

    for expected in [
        "401: The access token expired",
        "403: Not allowed to read application billing in environment prod",
        "404: No settings for application billing in environment prod",
        "500: Internal error",
    ] {
        assert!(!client.refresh().await);
        assert_eq!(client.problem().as_deref(), Some(expected));
        assert_eq!(client.get("pool").as_deref(), Some("10"));
    }

    client.refresh().await;
    assert_eq!(client.problem(), None);
}

#[tokio::test]
async fn an_unreachable_service_keeps_the_values() {
    let service = FakeService::start().await;
    service.settings(42, V42);
    let client = service.builder().build().await.unwrap();
    drop(service);
    tokio::task::yield_now().await;

    client.refresh().await;

    assert!(
        client
            .problem()
            .unwrap()
            .starts_with("cannot reach the config service")
    );
    assert_eq!(client.get("pool").as_deref(), Some("10"));
}

#[tokio::test]
async fn a_refused_token_is_retried_once_at_once_when_a_new_token_is_available() {
    let service = FakeService::start().await;
    service
        .error("401 Unauthorized", "The access token expired", &[])
        .settings(42, V42);
    let reads = Arc::new(Mutex::new(0));
    let counter = reads.clone();

    let client = service
        .builder()
        .token_fn(move || {
            let mut count = counter.lock().unwrap();
            *count += 1;
            Ok(if *count == 1 {
                "old-token".to_string()
            } else {
                "new-token".to_string()
            })
        })
        .build()
        .await
        .unwrap();

    assert_eq!(client.version(), 42);
    let tokens: Vec<_> = service
        .requests()
        .iter()
        .map(|r| r.header("authorization").unwrap())
        .collect();
    assert_eq!(tokens, ["bearer old-token", "bearer new-token"]);
}

#[tokio::test]
async fn a_refused_token_is_not_retried_when_it_has_not_changed() {
    let service = FakeService::start().await;
    service.error("401 Unauthorized", "The access token is not valid", &[]);

    let client = service.builder().build().await.unwrap();

    assert_eq!(service.requests().len(), 1);
    assert_eq!(
        client.problem().as_deref(),
        Some("401: The access token is not valid")
    );
}

#[tokio::test]
async fn the_token_file_is_read_for_every_request_and_a_missing_one_is_a_problem() {
    let service = FakeService::start().await;
    service.settings(42, V42).not_modified(42);
    let dir = std::env::temp_dir().join(format!("config-client-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let file = dir.join("token");
    std::fs::write(&file, "first\n").unwrap();
    let client = service.builder().token_file(&file).build().await.unwrap();

    std::fs::write(&file, "second\n").unwrap();
    client.refresh().await;
    std::fs::remove_file(&file).unwrap();
    client.refresh().await;
    std::fs::remove_dir(&dir).ok();

    let tokens: Vec<_> = service
        .requests()
        .iter()
        .map(|r| r.header("authorization").unwrap())
        .collect();
    assert_eq!(tokens, ["bearer first", "bearer second"]);
    assert!(
        client
            .problem()
            .unwrap()
            .starts_with("cannot get a bearer token")
    );
    assert_eq!(client.get("pool").as_deref(), Some("10"));
}

#[tokio::test]
async fn defaults_answer_until_settings_arrive_then_only_for_missing_keys() {
    let service = FakeService::start().await;
    service
        .error(
            "503 Service Unavailable",
            "The datastore cannot be reached",
            &[("Retry-After", "5")],
        )
        .settings(42, &[("pool", "10")]);
    let defaults = HashMap::from([
        ("pool".to_string(), "1".to_string()),
        ("timeout".to_string(), "5s".to_string()),
    ]);
    let client = service.builder().defaults(defaults).build().await.unwrap();

    assert!(!client.settings_loaded());
    assert_eq!(client.get("pool").as_deref(), Some("1"));

    client.refresh().await;

    assert_eq!(client.get("pool").as_deref(), Some("10"));
    assert_eq!(client.get("timeout").as_deref(), Some("5s"));
}

#[tokio::test]
async fn require_settings_at_startup_fails_the_build() {
    let service = FakeService::start().await;
    service.error(
        "403 Forbidden",
        "Not allowed to read application billing in environment prod",
        &[],
    );

    let error = service
        .builder()
        .require_settings_at_startup(true)
        .build()
        .await
        .unwrap_err();

    assert_eq!(
        error,
        ConfigClientError::Startup(
            "Cannot read billing/prod settings from the config service: \
             403: Not allowed to read application billing in environment prod"
                .to_string()
        )
    );
}

#[tokio::test]
async fn the_builder_needs_a_service_and_a_token() {
    let missing_url = ConfigClient::builder(AppEnvironment::new("billing", "prod"))
        .token("t")
        .build()
        .await;
    assert!(matches!(missing_url, Err(ConfigClientError::Builder(m)) if m.contains("base_url")));
    let missing_token = ConfigClient::builder(AppEnvironment::new("billing", "prod"))
        .base_url("http://127.0.0.1:1")
        .build()
        .await;
    assert!(matches!(missing_token, Err(ConfigClientError::Builder(m)) if m.contains("token")));
}

#[tokio::test]
async fn the_next_check_follows_max_age_with_jitter_and_a_one_second_floor() {
    let service = FakeService::start().await;
    service
        .settings_with(
            42,
            V42,
            "private, max-age=120, stale-while-revalidate=120, stale-if-error=600",
        )
        .settings_with(42, V42, "private, max-age=0")
        .settings(42, V42);
    let client = service.builder().build().await.unwrap();
    assert_eq!(client.next_check(), Duration::from_secs(120));

    client.refresh().await;
    assert_eq!(client.next_check(), Duration::from_secs(1));

    let jittered = service.builder().jitter(|| 1.0).build().await.unwrap();
    assert_eq!(jittered.next_check(), Duration::from_secs(33));
}

#[tokio::test]
async fn retry_after_is_honored_when_longer_than_max_age() {
    let service = FakeService::start().await;
    service
        .settings(42, V42)
        .error(
            "429 Too Many Requests",
            "Too many requests",
            &[("Retry-After", "120")],
        )
        .error(
            "503 Service Unavailable",
            "The datastore cannot be reached",
            &[("Retry-After", "5")],
        );
    let client = service.builder().build().await.unwrap();

    client.refresh().await;
    assert_eq!(client.next_check(), Duration::from_secs(120));

    client.refresh().await;
    assert_eq!(client.next_check(), Duration::from_secs(30));
}

#[tokio::test]
async fn settings_go_stale_after_max_age_plus_stale_if_error() {
    let service = FakeService::start().await;
    service
        .settings(42, V42)
        .settings_with(42, V42, "private, max-age=0, stale-if-error=0");
    let client = service.builder().build().await.unwrap();
    assert!(!client.stale());

    client.refresh().await;
    tokio::time::sleep(Duration::from_millis(20)).await;

    assert!(client.stale());
    assert_eq!(client.get("pool").as_deref(), Some("10"));
}

#[tokio::test]
async fn never_loaded_is_stale() {
    let service = FakeService::start().await;
    service.error(
        "503 Service Unavailable",
        "The datastore cannot be reached",
        &[],
    );

    let client = service.builder().build().await.unwrap();

    assert!(client.stale());
}

#[tokio::test]
async fn the_background_task_checks_after_max_age_until_dropped() {
    let service = FakeService::start().await;
    let every_second = "private, max-age=1, stale-while-revalidate=1, stale-if-error=60";
    service
        .settings_with(42, V42, every_second)
        .settings_with(43, &[("pool", "20")], every_second);
    let client = service.builder().build().await.unwrap();

    let task = client.spawn_refresh();
    for _ in 0..100 {
        if client.version() == 43 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert_eq!(client.version(), 43);
    assert_eq!(
        service.requests()[1].header("if-none-match").as_deref(),
        Some("\"42\"")
    );

    task.stop();
    let requests = service.requests().len();
    tokio::time::sleep(Duration::from_millis(1_500)).await;
    assert_eq!(service.requests().len(), requests);
}

#[test]
fn cache_control_parsing() {
    assert_eq!(
        CachePolicy::parse(Some(
            "private, max-age=120, stale-while-revalidate=60, stale-if-error=600"
        )),
        CachePolicy {
            max_age: Duration::from_secs(120),
            stale_while_revalidate: Duration::from_secs(60),
            stale_if_error: Duration::from_secs(600),
        }
    );
    assert_eq!(CachePolicy::parse(None), CachePolicy::DEFAULT);
    assert_eq!(
        CachePolicy::parse(Some("private, max-age=soon, stale-if-error=-1")),
        CachePolicy::DEFAULT
    );
    assert_eq!(
        CachePolicy::parse(Some("MAX-AGE=10")),
        CachePolicy {
            max_age: Duration::from_secs(10),
            stale_while_revalidate: Duration::from_secs(10),
            stale_if_error: DEFAULT_STALE_IF_ERROR,
        }
    );
}
