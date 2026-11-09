# Architecture

## Concepts

- **Application**: a consumer of configuration, such as `billing`.
- **Environment**: a deployment context for an application, such as `dev` or `prod`.
- **Configuration set**: the keys and values for one application in one environment
  (`AppEnvironment` in `config-model`).

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

## Java compatibility

The build uses a JDK 25 toolchain and compiles every module with `--release 21`, so published
artifacts run on Java 21 and later.

## Open decisions

- HTTP framework for `config-server`.
- Whether `config-client` is hand-written or generated from the OpenAPI spec.
- Storage schema per backend, and how environments inherit or override values.
- Versioning and history of configuration sets.
