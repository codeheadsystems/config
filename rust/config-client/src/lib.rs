//! Rust client for the config service HTTP API (`openapi/config-api.yaml`).
//!
//! The HTTP layer is the generated [`api`] crate (`config-api`, made from the spec by
//! `./gradlew :config-client:generateRustClient`); never edit it by hand.

pub use config_api as api;
pub use config_model::AppEnvironment;
