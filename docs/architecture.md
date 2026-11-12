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
                                        +-------------+-----------+-----------+
                                        v             v                       v
                                   postgresql      dynamodb                  etcd
```

1. **Through the service.** Clients call `config-server` over HTTP. The API contract lives in
   `openapi/config-api.yaml`; the Java `config-client` and the Rust `config-client` crate are
   built against it, and any other language can generate a client from it.
2. **Directly from the datastore.** `config-library` (Java) and the `config-library` crate (Rust)
   read the datastore themselves, for applications that should not depend on the service being up.

## Datastores

`config-datastore` defines the SPI. Each backend is its own module so an application only pulls in
the driver it uses, and backends are discovered with `java.util.ServiceLoader`.
`config-datastore-testkit` holds the contract tests; every backend's test suite extends them, so
all backends behave identically.

### Export

Exporting is a capability of `config-datastore` itself, not of any one backend: any datastore's
data can be exported, so any store (PostgreSQL, DynamoDB, etcd) can produce a snapshot that seeds
another. (Not built yet: the SPI first needs a way to list an application's environments and keys.)

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
- Storage schema per backend, and how environments inherit or override values.
- Versioning and history of configuration sets.
