//! Runs a [`ConfigClient`] for config-integ's end-to-end tests, which are written in Java and drive
//! this program over its standard streams. Not published, and not an example of how to use the
//! client: a real application calls `spawn_refresh` instead of refreshing on command.
//!
//! Usage: `config-integ-driver <base-url> <application> <environment> <token-file>`
//!
//! It builds the client (requiring settings at startup), then prints one JSON line describing the
//! client. After that it reads one command per line from standard input and answers each with one
//! JSON line:
//!
//! - `refresh`: checks the config service now; the answer's `changed` says whether new settings
//!   were loaded.
//! - `state`: describes the client without checking.
//! - `quit` (or end of input): exits.
//!
//! A description is `{"ok":true,"changed":..,"version":..,"settings":{..},"problem":..}`. If the
//! client cannot be built it prints `{"ok":false,"error":".."}` and exits with status 1.

use std::io::{BufRead, Write};
use std::process::ExitCode;
use std::time::Duration;

use config_client::{AppEnvironment, ConfigClient};
use serde_json::{Value, json};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [base_url, application, environment, token_file] = args.as_slice() else {
        eprintln!("usage: config-integ-driver <base-url> <application> <environment> <token-file>");
        return ExitCode::from(2);
    };
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("a Tokio runtime");

    let built = runtime.block_on(
        ConfigClient::builder(AppEnvironment::new(application, environment))
            .base_url(base_url)
            .token_file(token_file)
            .timeout(Duration::from_secs(10))
            .require_settings_at_startup(true)
            .build(),
    );
    let client = match built {
        Ok(client) => client,
        Err(e) => {
            answer(&json!({"ok": false, "error": e.to_string()}));
            return ExitCode::FAILURE;
        }
    };
    answer(&describe(&client, false));

    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        match line.trim() {
            "refresh" => {
                let changed = runtime.block_on(client.refresh());
                answer(&describe(&client, changed));
            }
            "state" => answer(&describe(&client, false)),
            "quit" => break,
            other => answer(&json!({"ok": false, "error": format!("unknown command: {other}")})),
        }
    }
    ExitCode::SUCCESS
}

fn describe(client: &ConfigClient, changed: bool) -> Value {
    json!({
        "ok": true,
        "changed": changed,
        "version": client.version(),
        "settings": *client.settings(),
        "problem": client.problem(),
    })
}

fn answer(value: &Value) {
    let mut out = std::io::stdout().lock();
    writeln!(out, "{value}").expect("stdout");
    out.flush().expect("stdout");
}
