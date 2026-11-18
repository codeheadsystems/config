# Changelog

Every notable change to the published modules, the HTTP API (`openapi/config-api.yaml`) and the
config service. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0, a minor
version may change the API.

A release needs a section here for its version: the release workflows refuse to tag or publish
without one and use it as the GitHub release notes (see [RELEASING.md](RELEASING.md)).

## [Unreleased]

## [0.1.0] - 2026-11-18

The first release. Java artifacts are on Maven Central under `com.codeheadsystems` and run on
Java 21 and later; `config-bom` aligns their versions.

### Added

- **Configuration model** (`config-model`): property-based configuration, flat dotted keys mapped to
  string values, stored per application and environment. `Configuration.with("database")` narrows
  lookups to one namespace. Application and environment names are DNS-label-like (1 to 63
  lowercase letters, digits, `-` or `_`). A `ConfigurationFactory` is bound to the one application
  and environment of the process using it.
- **Datastore SPI** (`config-datastore`): `ConfigReader` and `ConfigWriter` over versioned sets of
  keys and values (`SETTINGS` and `METADATA` per application and environment), discovered through
  `ServiceLoader` with `ConfigDatastores.open(name, options)`. Versions only increase, including
  across a delete and re-create. Includes the in-memory `memory` backend. Classes meant for a
  container carry JSR-330 annotations, so Dagger, Spring or Guice can wire them.
- **Datastore backends**:
  - `config-datastore-sql`: PostgreSQL, on JDBI and Flyway.
  - `config-datastore-dynamodb`: Amazon DynamoDB, also DynamoDB Local.
  - `config-datastore-s3`: Amazon S3, also RustFS 1.0.4 and later (needs conditional writes).
- **Datastore contract tests** (`config-datastore-testkit`): the suite every backend passes,
  published so third-party backends can run the same contract.
- **In-process library** (`config-library`): `LibraryConfigurationFactory` reads a datastore
  directly, with an updates datastore layered over a defaults datastore (either may be left
  out), a background refresh that
  reloads only when the version changes, and last-known values kept through datastore failures.
- **Config service** (`config-server`, a Dropwizard application): serves
  `GET` and `HEAD /v1/applications/{application}/environments/{environment}/settings`, read-only.
  - Version checks with `ETag`, `If-None-Match` and `304 Not Modified`.
  - `Cache-Control` with `max-age`, `stale-while-revalidate` and `stale-if-error`, set per
    application from its `METADATA` (`cache.max-age`, `cache.stale-if-error`).
  - JWT bearer tokens from configured OpenID Connect issuers: keys from discovery, cached, and
    refetched on an unknown `kid` within limits, so key rotation needs no restart. Asymmetric
    algorithms only (RS, PS, ES); `none` and HMAC are refused.
  - Access per subject, granted in the application's `METADATA` with
    `auth.<issuer name>.subjects`; `403` does not reveal whether settings exist.
  - A rate limit per token, `429` and `503` with `Retry-After`, health checks for the datastore
    and every issuer, and metrics on the admin port.
  - Startup validation that refuses settings which would turn a protection off.
- **Java client** (`config-client`): `ClientConfigurationFactory` reads through the config service.
  Lookups are local, one conditional `GET` per check with jitter, versions only move forward,
  errors never drop the values held, and tokens are read on every request from a supplier or a
  file (for Kubernetes projected tokens). Its HTTP layer is generated from the OpenAPI spec.
- **Rust client** (`rust/config-client`, not yet on crates.io): `ConfigClient`, with the same rules
  as the Java client, over the `config-api` crate generated from the same spec.
- **HTTP API contract**: `openapi/config-api.yaml` (OpenAPI 3.1), for generating clients in other
  languages.

### Known limitations

- The etcd backend (`config-datastore-etcd`) is a placeholder and is not published.
- Exporting a datastore's data is designed but not built yet.
- `config-server` is not published as an artifact or a container image; build it from source.
- The Rust crates are not published to crates.io.

[Unreleased]: https://github.com/codeheadsystems/config/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/codeheadsystems/config/releases/tag/v0.1.0
