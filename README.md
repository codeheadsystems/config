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
| `config-datastore-dynamodb/` | Amazon DynamoDB backend (also works with DynamoDB Local). | Published |
| `config-datastore-etcd/` | etcd backend. | Placeholder |
| `config-library/` | In-process Java library that reads a datastore directly. | Published |
| `config-client/` | Java client for the config service: `ClientConfigurationFactory`, over an HTTP layer generated from the spec at build time. Also generates the Rust `config-api` crate. | Published |
| `config-server/` | The config service: a Dropwizard application wired with Dagger. | Not published |
| `config-bom/` | BOM aligning every published `config-*` module. | Published |
| `config-integ/` | End-to-end tests against real datastores, for the Java and Rust clients (needs Docker and cargo). | Not published |
| `config-test-oidc/` | In-process OpenID Connect issuer that signs test tokens, valid and forged, for `config-server`'s tests. (`config-integ` uses mini-auth's mini-idp instead.) | Not published |
| `openapi/` | The HTTP API contract (`config-api.yaml`), shared by every client. | |
| `rust/` | Cargo workspace: `config-model`, `config-api` (generated from the spec; do not edit), `config-client`, and `config-integ-driver` (runs the Rust client in `config-integ`; not published). Rust reads configuration only through the config service. | |
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

The clients' HTTP layers are generated from `openapi/config-api.yaml`. The Java one is generated
on every build. The Rust crate `rust/config-api` is committed, because Cargo cannot run the
generator; after changing the spec, regenerate it and commit the result (`./gradlew build` fails
while it is out of date):

```sh
./gradlew :config-client:generateRustClient
```

Run the config service locally (in-memory datastore; see `config-server/config.yml`):

```sh
./gradlew :config-server:run --args="server config-server/config.yml"
```

## Testing

### What you need

| Tool | Used by |
|------|---------|
| JDK 25 | Every Java test. |
| Docker | The backend tests (`config-datastore-sql`, `-dynamodb`, `-s3`) and `config-integ`. |
| Rust 1.85+ with `rustfmt` and `clippy` (cargo on the `PATH`) | The Rust tests, and `config-integ`'s Rust client scenarios. |

Testcontainers starts and removes the containers itself, using the images pinned in the tests
(PostgreSQL, DynamoDB Local and RustFS). You don't need `docker compose` for testing.

### Run everything

This is what CI runs:

```sh
./gradlew build
cd rust
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test --all
```

`./gradlew build` runs:

- every Java module's tests;
- each backend's datastore contract tests against its container;
- the `config-server` tests;
- the `config-integ` end-to-end tests, which cover every backend through the Java client, the Rust
  client and the library;
- `checkRustClient`, which fails if `rust/config-api` is out of date with the spec.

To run the Java tests without the rest of the build, use `./gradlew test`.

### Skipped tests

Tests that need Docker or cargo skip on a developer machine when it is missing. They fail instead
when the `CI` environment variable is set. To make sure nothing was skipped, run:

```sh
CI=true ./gradlew build
```

### Run part of the tests

```sh
./gradlew :config-server:test                                # one module
./gradlew :config-integ:test --tests '*PostgresEndToEndTest'  # one test class
./gradlew :config-integ:test --tests '*PostgresEndToEndTest.theRustClient*' # matching methods
./gradlew :config-integ:test --rerun                         # rerun tests Gradle considers up to date
cd rust && cargo test -p config-client                       # one Rust crate
```

The end-to-end scenarios live in `EndToEndContract` but run under each backend's subclass, so a
`--tests` filter names the subclass (`PostgresEndToEndTest`, `DynamodbEndToEndTest` or
`S3EndToEndTest`).

### Reports

Each module writes test reports to `build/reports/tests/test/index.html` and coverage reports to
`build/reports/jacoco/test/html/index.html`.

## Local datastores

For manual testing, start the datastores with:

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

Applications that read through the config service use `config-client` instead:

```kotlin
dependencies {
    implementation(platform("com.codeheadsystems:config-bom:<version>"))
    implementation("com.codeheadsystems:config-client")
}
```

```java
try (ClientConfigurationFactory factory = ClientConfigurationFactory.builder()
    .with(Application.from("billing"))
    .with(Environment.from("prod"))
    .baseUri(URI.create("https://config.example.com"))
    .tokenFile(Path.of("/var/run/secrets/tokens/config-token"))
    .build()) {
  Configuration configuration = factory.configuration();
}
```

Rust applications use the `config-client` crate in `rust/` (`ConfigClient`), which follows the
same rules.
