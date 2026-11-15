//! The generated `config-api` client against responses exactly as `openapi/config-api.yaml`
//! describes them, served by a one-shot local HTTP server. Checks what the hand-written layer
//! relies on: status and headers on every response (the version is in headers), `304` without a
//! body, and the `Error` body on errors.

use config_client::api::apis::Error;
use config_client::api::apis::configuration::Configuration;
use config_client::api::apis::settings_api::{self, GetSettingsSuccess, GetSettingsVersionSuccess};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

const CACHE_CONTROL: &str = "private, max-age=30, stale-while-revalidate=30, stale-if-error=86400";

/// Serves one canned response; the handle yields the raw request the client sent.
async fn serve_once(response: String) -> (Configuration, JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let request = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut buffer = vec![0u8; 16 * 1024];
        let read = socket.read(&mut buffer).await.unwrap();
        socket.write_all(response.as_bytes()).await.unwrap();
        socket.shutdown().await.ok();
        String::from_utf8_lossy(&buffer[..read]).to_lowercase()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://{address}");
    configuration.bearer_access_token = Some("test-token".to_string());
    (configuration, request)
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

fn header(headers: &reqwest::header::HeaderMap, name: &str) -> String {
    headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default()
        .to_string()
}

#[tokio::test]
async fn get_settings_returns_the_set_with_its_version_headers() {
    let body = r#"{"application":"billing","environment":"prod","version":42,"values":{"database.url":"jdbc:postgresql://db:5432/billing","database.pool.size":"10"}}"#;
    let (configuration, request) = serve_once(response(
        "200 OK",
        &[
            ("ETag", "\"42\""),
            ("Config-Version", "42"),
            ("Cache-Control", CACHE_CONTROL),
        ],
        body,
    ))
    .await;

    let result = settings_api::get_settings(&configuration, "billing", "prod", None)
        .await
        .unwrap();

    assert_eq!(result.status, 200);
    assert_eq!(header(&result.headers, "config-version"), "42");
    assert_eq!(header(&result.headers, "etag"), "\"42\"");
    assert_eq!(header(&result.headers, "cache-control"), CACHE_CONTROL);
    match result.entity {
        Some(GetSettingsSuccess::Status200(settings)) => {
            assert_eq!(settings.version, 42);
            assert_eq!(
                settings.values["database.url"],
                "jdbc:postgresql://db:5432/billing"
            );
            assert_eq!(settings.values["database.pool.size"], "10");
        }
        other => panic!("expected the settings, got {other:?}"),
    }
    let request = request.await.unwrap();
    assert!(
        request.starts_with("get /v1/applications/billing/environments/prod/settings "),
        "{request}"
    );
    assert!(
        request.contains("authorization: bearer test-token"),
        "{request}"
    );
}

#[tokio::test]
async fn get_settings_with_the_current_version_is_304_without_a_body() {
    let (configuration, request) = serve_once(response(
        "304 Not Modified",
        &[
            ("ETag", "\"42\""),
            ("Config-Version", "42"),
            ("Cache-Control", CACHE_CONTROL),
        ],
        "",
    ))
    .await;

    let result = settings_api::get_settings(&configuration, "billing", "prod", Some("\"42\""))
        .await
        .unwrap();

    assert_eq!(result.status, 304);
    assert_eq!(header(&result.headers, "config-version"), "42");
    assert!(!matches!(
        result.entity,
        Some(GetSettingsSuccess::Status200(_))
    ));
    assert!(request.await.unwrap().contains("if-none-match: \"42\""));
}

#[tokio::test]
async fn head_returns_the_version_in_headers() {
    let (configuration, request) = serve_once(response(
        "200 OK",
        &[
            ("ETag", "\"43\""),
            ("Config-Version", "43"),
            ("Cache-Control", CACHE_CONTROL),
        ],
        "",
    ))
    .await;

    let result =
        settings_api::get_settings_version(&configuration, "billing", "prod", Some("\"42\""))
            .await
            .unwrap();

    assert_eq!(result.status, 200);
    assert_eq!(header(&result.headers, "config-version"), "43");
    assert!(matches!(
        result.entity,
        None | Some(GetSettingsVersionSuccess::Status200())
    ));
    assert!(
        request
            .await
            .unwrap()
            .starts_with("head /v1/applications/billing/environments/prod/settings ")
    );
}

#[tokio::test]
async fn errors_carry_status_headers_and_the_error_message() {
    let (configuration, _request) = serve_once(response(
        "503 Service Unavailable",
        &[("Retry-After", "30")],
        r#"{"message":"The datastore cannot be reached"}"#,
    ))
    .await;

    let error = settings_api::get_settings(&configuration, "billing", "prod", None)
        .await
        .unwrap_err();

    let Error::ResponseError(content) = error else {
        panic!("expected a response error, got {error:?}")
    };
    assert_eq!(content.status, 503);
    assert_eq!(header(&content.headers, "retry-after"), "30");
    // The error enum is untagged and every variant holds the same model, so serde picks the first
    // variant whatever the status: callers go by `status`, never by the variant name.
    let message = match content.entity {
        Some(settings_api::GetSettingsError::UnknownValue(value)) => {
            value["message"].as_str().unwrap().to_string()
        }
        Some(other) => serde_json::to_value(other).unwrap()["message"]
            .as_str()
            .unwrap()
            .to_string(),
        None => panic!("the error body was not parsed: {}", content.content),
    };
    assert_eq!(message, "The datastore cannot be reached");
}
