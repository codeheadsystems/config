# config

A configuration service and client libraries. Configuration is stored per **application** and
**environment** in a pluggable datastore (PostgreSQL, DynamoDB, etcd, S3, ...) and read either through
the config service's HTTP API or directly from the datastore with an in-process library.
Configuration is property-based: flat keys (namespaced with dots, such as `database.url`) mapped to
string values. Any datastore's data can be exported.

Licensed under the [MIT License](LICENSE).

## Repository layout

| Path | What it is | Status |
|------|------------|--------|
| `config-model/` | Core domain types (applications, environments, keys, values). No I/O. | Published |
| `config-datastore/` | Datastore SPI that every backend implements, plus an in-memory backend. | Published |
| `config-datastore-s3/` | Amazon S3 backend (also works with RustFS). | Published |
| `config-datastore-sql/` | SQL backend (PostgreSQL), on JDBI SqlObjects and Flyway. | Published |
| `config-datastore-testkit/` | Contract tests every backend must pass. | Published |
| `config-datastore-dynamodb/` | DynamoDB backend. | Placeholder |
| `config-datastore-etcd/` | etcd backend. | Placeholder |
| `config-library/` | In-process Java library that reads a datastore directly. | Published |
| `config-client/` | Java client for the config service HTTP API. | Placeholder |
| `config-server/` | The config service. | Placeholder |
| `config-bom/` | BOM aligning every published `config-*` module. | Published |
| `config-integ/` | End-to-end tests against real datastores. | Not published |
| `openapi/` | The HTTP API contract (`config-api.yaml`), shared by every client. | |
| `rust/` | Cargo workspace: `config-model` and `config-client`. Rust reads configuration only through the config service. | |
| `buildSrc/` | Gradle convention plugins. | |
| `docs/` | Design and usage documentation. | |

Placeholder modules are not published and are not in the BOM until they are implemented.

## Building

Java (needs JDK 25 to build; artifacts run on Java 21+):

```sh
./gradlew build
```

Rust:

```sh
cd rust && cargo test
```

Local datastores for manual testing:

```sh
docker compose up -d
```

## Coordinates

Java artifacts are published to Maven Central under group `com.codeheadsystems` (Java packages are
`com.codeheadsystems.config.*`). Nothing has been released yet; see [RELEASING.md](RELEASING.md).

```kotlin
dependencies {
    implementation(platform("com.codeheadsystems:config-bom:<version>"))
    implementation("com.codeheadsystems:config-library")
    runtimeOnly("com.codeheadsystems:config-datastore-s3")
}
```

`config-client`, the entry point for applications that call the config service, is not published
yet.
