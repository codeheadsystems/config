# Architecture

## Concepts

Configuration is property-based: a configuration set is a flat map of keys to string values.
Keys are namespaced with `.` (`database.url`, `database.pool.size`), and
`configuration.with("database")` narrows lookups to one namespace.

- **Application**: the service that is running, named by its service name, such as `billing`.
- **Environment**: where that service is running, such as `test` or `prod`.

  Both names are 1 to 63 lowercase letters, digits, `-` or `_`, starting with a letter or digit
  (like a DNS label). Lowercase only, so two names never differ just by case; no `/` or `.`, so a
  name is always safe as one segment of a path, URL or storage key.
- **Configuration set**: the keys and values for one application in one environment
  (`Application` and `Environment` in `config-model`).
- **Configuration factory**: builds the `Configuration` for a running process. Each factory is
  bound to one application and one environment, the identity of the process using it, so a
  service only ever reads its own configuration set. config-library and config-client provide
  the implementations; datastore backends do not.

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
- `validate(Access.READ | Access.READ_WRITE)` checks a datastore is usable before relying on it:
  reachable, the right permissions for that access, and behaving as the contract needs. It
  returns every check made; `requireOk()` throws listing the failures. Run it at startup.

The testkit's `ConfigDatastoreContract` checks all of this, including under concurrent writers:
no lost updates, one winner per expected version, and no version ever reused across creates and
deletes.

Backends that store a whole set as one document (S3, DynamoDB, etcd) can use `Changes` to apply a
write, so they all interpret changes the same way.

### S3 backend

`config-datastore-s3` stores each set as one object:

```
{prefix}sets/{application}/{environment}/{settings|metadata}   one object per set
{prefix}_validate/{random}                                     short-lived validate() probes
```

- A live set is JSON, `{"format":1,"version":n,"values":{...}}`, with the version and state also
  in object metadata, so `version()` is a single `HEAD`. Readers ignore unknown fields; a change
  older readers cannot ignore must raise `format`, which older readers then refuse clearly.
- A set's key is never removed. **Deleting** overwrites the set with a 16-byte marker (the version
  in hex) that keeps its version; **creating** it again overwrites the marker. Every live document
  is longer than 16 bytes, so listings skip markers using the sizes S3 already returns.
- So every write (create, update or delete) is **one conditional request on one key**:
  `If-Match` on the ETag just read, or `If-None-Match: *` for a key never used. Any other write in
  between changes the ETag and fails the request, so writes are atomic and a version is never
  reused, even across a delete.
- A write that loses a race starts again from a fresh read. With a specific expected version that
  ends in a `VersionConflictException` carrying the real current version; with `Versions.ANY` it
  retries with jittered backoff, up to 50 attempts.

It needs an S3 service that enforces conditional writes: Amazon S3, or RustFS (pinned to 1.0.4 in
`docker-compose.yml` and `RustFs.java` in the tests). Permissions: readers need `s3:ListBucket`
and `s3:GetObject` (without `ListBucket`, S3 answers 403 instead of 404 for a missing set);
writers also need `s3:PutObject`. Objects are never deleted, so `s3:DeleteObject` is needed only to
clean up `validate()` probes. `validate()` checks all of this, including that the store really
rejects a stale `If-Match` and a repeated `If-None-Match`.

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
- Storage layout for the remaining backends (S3 uses one object per set with a deleted-set marker;
  the same approach likely suits DynamoDB and etcd), and how environments inherit or override
  values.
- Cleaning up deleted-set markers. They are 16 bytes each and keep versions monotonic, so they are
  kept for now.
- History of configuration sets (each set already has a version that only increases).
