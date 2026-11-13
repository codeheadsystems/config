# config

A configuration service and client libraries. Configuration is stored per **application** and
**environment** in a pluggable datastore (PostgreSQL, DynamoDB, etcd, S3, ...) and read either through
the config service's HTTP API or directly from the datastore with an in-process library.
Configuration is property-based: flat keys (namespaced with dots, such as `database.url`) mapped to
string values. Any datastore's data can be exported.

Licensed under the [MIT License](LICENSE).

## Repository layout

| Path | What it is |
|------|------------|
| `config-model/` | Core domain types (applications, environments, keys, values). No I/O. |
| `config-datastore/` | Datastore SPI that every backend implements, plus an in-memory backend. |
| `config-datastore-postgresql/` | PostgreSQL backend. |
| `config-datastore-dynamodb/` | DynamoDB backend. |
| `config-datastore-etcd/` | etcd backend. |
| `config-datastore-s3/` | Amazon S3 backend. |
| `config-datastore-testkit/` | Contract tests every backend must pass. |
| `config-library/` | In-process Java library that reads a datastore directly. |
| `config-client/` | Java client for the config service HTTP API. |
| `config-server/` | The config service. |
| `config-bom/` | BOM aligning every published `config-*` module. |
| `config-integ/` | End-to-end tests against real datastores. Not published. |
| `openapi/` | The HTTP API contract (`config-api.yaml`), shared by every client. |
| `rust/` | Cargo workspace: `config-model`, `config-client`, `config-library`. |
| `buildSrc/` | Gradle convention plugins. |
| `docs/` | Design and usage documentation. |

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

Java artifacts are published to Maven Central under group `com.codeheadsystems` (Java packages are `com.codeheadsystems.config.*`):

```kotlin
dependencies {
    implementation(platform("com.codeheadsystems:config-bom:<version>"))
    implementation("com.codeheadsystems:config-library")
    runtimeOnly("com.codeheadsystems:config-datastore-postgresql")
}
```
