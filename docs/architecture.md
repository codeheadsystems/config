# Architecture

## Concepts

Configuration is property-based: a configuration set is a flat map of keys to string values.
Keys are namespaced with `.` (`database.url`, `database.pool.size`), and
`configuration.with("database")` narrows lookups to one namespace.

- **Application**: the service that is running, named by its service name, such as `billing`.
- **Environment**: where that service is running, such as `test` or `prod`.
- **Configuration set**: the keys and values for one application in one environment
  (`Application` and `Environment` in `config-model`).
- **Configuration factory**: builds the `Configuration` for a running process. Each factory is
  bound to one application and one environment, the identity of the process using it, so a
  service only ever reads its own configuration set. Each datastore backend provides one.

## Two ways to read configuration

```
                   +----------------+        HTTP (openapi/config-api.yaml)
 app (any lang) -->| config-client  |-----------------------------+
                   +----------------+                             v
                                                          +---------------+
                                                          | config-server |
                                                          +-------+-------+
                                                                  |
                   +----------------+                             v
 app (Java/Rust)-->| config-library |-------------------> config-datastore SPI
                   +----------------+                             |
                                  +-------------+-------------+-------------+
                                  v             v             v             v
                             postgresql     dynamodb         etcd           s3
```

1. **Through the service.** Clients call `config-server` over HTTP. The API contract lives in
   `openapi/config-api.yaml`; the Java `config-client` and the Rust `config-client` crate are
   built against it, and any other language can generate a client from it.
2. **Directly from the datastore.** `config-library` (Java) and the `config-library` crate (Rust)
   read the datastore themselves, for applications that should not depend on the service being up.

## Datastores

`config-datastore` defines the SPI. Each backend is its own module so an application only pulls in
the driver it uses, and backends are discovered with `java.util.ServiceLoader` through
`ConfigDatastoreProvider` (`ConfigDatastores.open(name, options)`).
`config-datastore-testkit` holds the contract tests; every backend's test suite extends them, so
all backends behave identically. `config-datastore` also ships an in-memory backend (`memory`),
the reference implementation the contract is checked against.

### Sets

A datastore holds **sets** of keys and values, one per application, environment and set type
(`SetId`):

- **`SETTINGS`**: the values served to the application. This is the only set config-client or
  config-library ever reads.
- **`METADATA`**: information the config service keeps about the application in that environment.
  Never served to the application.

Readers use `ConfigReader` (config-library needs only this, so it can run read-only). Writers use
`ConfigWriter`:

- `apply(id, expectedVersion, changes)` makes a list of changes (`Put`, `Delete`, `Clear`)
  atomically. `put`, `remove` and `replace` are built on it.
- Every write takes an expected version: the set's current version, `Versions.NONE` ("only if it
  does not exist") or `Versions.ANY` (no check). A mismatch throws `VersionConflictException`.
- A set's version only ever increases, even across a delete, so a reader that remembers a version
  can tell when a set has changed.
- Each backend sets its own `maxSetBytes()`, measured by `SetSize` (UTF-8 bytes of key names and
  values). A write that would exceed it throws `SetTooLargeException` and changes nothing.

Backends that store a whole set as one document (S3, DynamoDB, etcd) can use `Changes` to apply a
write, so they all interpret changes the same way.

### S3 backend

`config-datastore-s3` stores each set as one JSON object, `{"version": n, "values": {...}}`, with
the version also in object metadata so `version()` is a single `HEAD`:

```
{prefix}sets/_{application}/_{environment}/{settings|metadata}   live sets (listed)
{prefix}tombstones/_{application}/_{environment}/{type}          last version of a deleted set
```

Names are URL-encoded and prefixed with `_`, so a `/` in a name never adds a level. Every write is
one conditional request (`If-None-Match: *` to create, `If-Match` to update, a conditional delete
to remove), which makes it atomic. Writes using `Versions.ANY` retry with jittered backoff when they
lose a race. It needs an S3 service with conditional writes and deletes: Amazon S3, or RustFS
(pinned to 1.0.4 in `docker-compose.yml` and the tests).

### Export

Exporting is a capability of `config-datastore` itself, not of any one backend: any datastore's
data can be exported, so any store can produce a snapshot that seeds another. (Not built yet; the
SPI's listings, `applications()`, `environments()` and `types()`, give it what it needs.)

## Layering in config-library

`config-library` reads configuration from a stack of datastores:

1. **Defaults.** A datastore loaded first. This can be an export of the live datastore, which
   gives the application a "cache load" to start from without reaching the live store.
2. **Updates.** A second datastore attached on top. Values it holds override the defaults.

A key is looked up in the updates datastore first and falls back to the defaults.

## Java compatibility

The build uses a JDK 25 toolchain and compiles every module with `--release 21`, so published
artifacts run on Java 21 and later.

## Open decisions

- How `config-library` refreshes the updates datastore (polling, watch, push), and what happens
  when the updates datastore is unreachable at startup.
- The export format.
- HTTP framework for `config-server`.
- Whether `config-client` is hand-written or generated from the OpenAPI spec.
- Storage layout for the remaining backends (S3 uses one object per set; the same approach likely
  suits DynamoDB and etcd), and how environments inherit or override values.
- History of configuration sets (each set already has a version that only increases).
