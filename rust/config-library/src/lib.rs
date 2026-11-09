//! Reads configuration directly from a datastore, mirroring the Java `config-library` module.
//! Backends (PostgreSQL, DynamoDB, etcd) will be cargo features.

pub use config_model::AppEnvironment;
