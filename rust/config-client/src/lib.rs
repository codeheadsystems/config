//! Rust client for the config service HTTP API (`openapi/config-api.yaml`).
//!
//! Applications use [`ConfigClient`]. The HTTP layer under it is the generated [`api`] crate
//! (`config-api`, made from the spec by `./gradlew :config-client:generateRustClient`); never edit
//! it by hand.

mod client;

pub use client::{
    ConfigClient, ConfigClientBuilder, ConfigClientError, DEFAULT_TIMEOUT, MINIMUM_INTERVAL,
    RefreshTask, TokenSource,
};
pub use config_api as api;
pub use config_model::AppEnvironment;
