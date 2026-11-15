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
 app (Java)     -->| config-library |-------------------> config-datastore SPI
                   +----------------+                             |
                                  +-------------+-------------+-------------+
                                  v             v             v             v
                             sql (pg)       dynamodb         etcd           s3
```

1. **Through the service.** Clients call `config-server` over HTTP. The API contract lives in
   `openapi/config-api.yaml`; the Java `config-client` and the Rust `config-client` crate are
   built against it, and any other language can generate a client from it. A client gets the
   `SETTINGS` set for its application and environment
   (`GET /v1/applications/{application}/environments/{environment}/settings`), or only its
   version with `HEAD` on the same URL, to poll for changes without fetching the values.
2. **Directly from the datastore.** `config-library` reads the datastore itself, for Java
   applications that should not depend on the service being up. It is Java only: the datastore
   backends exist only in Java, so other languages, Rust included, read through the service.

Both ways are read-only. No client can change configuration: changes are made only in-process,
by Java code calling `ConfigWriter` from `config-datastore`.

### Polling the service

Every settings response carries
`Cache-Control: private, max-age=30, stale-while-revalidate=30, stale-if-error=86400` (with each
application's own values). It tells clients how long to use their values:

- **`max-age`**: before checking the version again (with `HEAD`, or `GET` with `If-None-Match`; an
  unchanged set answers `304 Not Modified`, which starts a new `max-age` period). Raising it slows
  every client of that application down without changing them; the cost is that a change can
  take up to `max-age` to reach them.
- **`stale-while-revalidate`** (RFC 5861): after `max-age`, the values are stale; for up to this
  long the client keeps serving them while the version check runs in the background. Always
  equal to `max-age`. If no check has succeeded by then, the `stale-if-error` rules apply.
  Lookups always read in-memory values, so they never wait on the network.
- **`stale-if-error`** (RFC 5861): while the service fails (`500`, `502`, `503`, `504`) or cannot be
  reached. Past it, clients still keep their values, as `config-library` does after a failed
  refresh, but report them as stale (logs, health checks).
- **`private`**: shared caches (proxies, CDNs) must not store responses, because settings can
  hold secrets.

The server reads these from the application's `METADATA` set in that environment, in seconds:
`cache.max-age` (default 30) and `cache.stale-if-error` (default 86400, one day).

Clients add up to 10% random jitter to each wait and honor `Retry-After` on `429` and `503`,
keeping the values they have. A client that has values keeps them on every error and reports
it: on `401` it gets a new token, on `403` (access revoked) and `404` (settings deleted) it keeps
checking every `max-age`.

`config-server` keeps each set's version in memory for a second, so a burst of version checks
costs one datastore query. A version can therefore lag, and several servers can disagree, so a
client only ever moves to a higher version and ignores a lower one. A single server never goes
backward: a fresh `GET` moves its cached version forward.

### Client authentication and access

Clients send a JWT as a bearer token (`Authorization: Bearer <jwt>`). The token proves who the
client is; what it may read is decided by the config service, from data kept with the
configuration. `config-server` never issues tokens.

**Trusted issuers.** `config-server` is configured with the OpenID Connect issuers it trusts.
Each has:

- a **name**, a valid key segment such as `k8s-prod`, used in METADATA below;
- the **issuer URL**, which must equal the token's `iss` exactly. It must be `https` (plain `http`
  only on loopback, for development and tests), checked at startup, because whoever serves the
  issuer's keys can mint tokens;
- the **audience**, which the token's `aud` must contain. It identifies the config service, so a
  token issued for another service is refused.

The issuer's signing keys come from OpenID Connect Discovery (`<issuer>/.well-known/openid-configuration`,
then its `jwks_uri`, which must also be `https`). They are cached for `auth.keyCacheTtl` (5
minutes) and refetched when a token names an unknown `kid`, so key rotation needs no restart.
Refetches for unknown `kid`s are limited to two per `auth.keyRefreshInterval` (30 seconds), so
made-up key IDs cannot make the server hammer the issuer; a token over that limit gets `503`
with `Retry-After`, because during a real rotation the same token works once the window passes.
While an issuer cannot be reached, the server keeps using the keys it has for
`auth.keyOutageTolerance` (1 day, matching the default `stale-if-error`); an unreachable issuer
with no usable keys is `503`. Any compliant issuer works: an OAuth server using the client
credentials grant, Kubernetes service account tokens, SPIFFE JWT-SVIDs.

**Checking a token** (any failure is `401`, with `WWW-Authenticate: Bearer error="invalid_token"`):
a signed JWT (not unsigned, not encrypted) with an asymmetric algorithm (RS, PS or ES at 256, 384
or 512; no `none` or HMAC), `iss` is a trusted issuer, the signature verifies with that issuer's
key, `aud` contains the issuer's audience, `exp` is in the future and `nbf` (if present) in the
past, with up to 60 seconds of clock skew, and `sub` is present and not blank.

**Access.** A valid token reads an application's settings in an environment only if its subject
is listed in that application's `METADATA` set for that environment:

```
auth.<issuer name>.subjects = <subject> <subject> ...
```

For example, `auth.k8s-prod.subjects = system:serviceaccount:billing:billing`. Subjects are
separated by whitespace. No list, an empty list, or a subject not on it means no access (`403`,
with no `WWW-Authenticate`: RFC 6750's `insufficient_scope` would send OAuth clients after more
scope, which cannot help when the server grants access). Because only
the library writes METADATA, access is granted and revoked the same way every other change is
made, and takes effect within the server's METADATA cache time.

**Order of checks.** Token (`401`), path names (`400`), access (`403`), then existence (`404`).
A client without access gets `403` whether or not the settings exist, so a token cannot be used
to find out which applications and environments exist.

**Tokens should be short-lived** (minutes to an hour), renewed by the client or its platform. A
bearer token works for whoever holds it, so its lifetime is how long a leaked one stays useful.
Bearer tokens are only sent over TLS (RFC 6750); plain HTTP is for local development.

**Rate limit.** Each verified token has a token bucket (`rateLimit`: bursts of 20, then 60 requests
a minute); over it is `429` with `Retry-After`. Limits are per token rather than per identity
because many instances of one service usually share an identity (one Kubernetes service account
for every pod) but each has its own token. Buckets are keyed by a hash of the token, never the
token itself.

**Testing.** `config-test-oidc` is an in-process OpenID Connect issuer for tests: it serves the
discovery document and JWKS and signs tokens with any claims, including invalid ones (expired,
wrong audience, unknown key). It is not published.

## Datastores

`config-datastore` defines the SPI. Each backend is its own module so an application only pulls in
the driver it uses, and backends are discovered with `java.util.ServiceLoader` through
`ConfigDatastoreProvider` (`ConfigDatastores.open(name, options)`).
`config-datastore-testkit` holds the contract tests; every backend's test suite extends them, so
all backends behave identically. `config-datastore` also ships an in-memory backend (`memory`),
the reference implementation the contract is checked against.

### Dependency injection

Classes meant to be built by a container carry the standard JSR-330 annotations from
`jakarta.inject` (`@Inject` on the constructor, `@Singleton` where one instance should be shared),
so Dagger, Spring, Guice or any other JSR-330 container can wire them. No container is a
dependency: the application picks one. So far that covers `InMemoryConfigDatastore` (no
arguments) and `SqlConfigDatastore` (needs a `Jdbi`, which the application provides over its own
connection pool). Constructors that need plain values, such as a size limit, are left unannotated;
call them from a provider method. `ServiceLoader` discovery through `ConfigDatastores` still works
for applications without a container.

### Sets

A datastore holds **sets** of keys and values, one per application, environment and set type
(`SetId`):

- **`SETTINGS`**: the values served to the application. This is the only set config-client or
  config-library ever reads.
- **`METADATA`**: internal bookkeeping about the application in that environment. Never served
  to the application or over the HTTP API.

Set types exist only in `config-datastore` and its backends; `config-model`, which clients see,
has no set type.

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

## config-server

A [Dropwizard](https://www.dropwizard.io) application, wired with [Dagger](https://dagger.dev)
(no Spring). Dropwizard owns the lifecycle, the HTTP server (Jetty, Jersey), configuration,
logging, metrics and health checks; Dagger builds the objects it registers, using the same
JSR-330 annotations as the rest of the project.

- **Read-only.** The server is given a `ConfigReader`, never a `ConfigWriter`; there is no write
  endpoint, so `PUT`, `POST`, `PATCH` and `DELETE` are `405`.
- **Configuration** (`config-server/config.yml` is an annotated example): `datastore` (a backend
  name and options, opened through `ConfigDatastores`), `auth` (trusted issuers, clock skew,
  issuer timeout, JWKS refresh limit) and `cache` (server-side TTLs and `Cache-Control`
  defaults).
- **Requests** go through `BearerTokenFilter` (token, `401`), then `SettingsResource` (names `400`,
  access `403`, existence `404`). Every error, including Jersey's own, uses the API's
  `{"message": ...}` body.
- **Server-side caches** (Caffeine): versions for `cache.versionTtl` (1 second) and METADATA for
  `cache.metadataTtl` (10 seconds). Settings values are always read fresh.
- **Token keys** (Nimbus JOSE+JWT): discovered per issuer on the first token or health check,
  cached, refetched on unknown `kid`s within limits, and kept through issuer outages; see
  "Client authentication and access". The key sources' background threads stop at shutdown.
- **Rate limit**: per token (`rateLimit`), after the token is verified, so forged tokens never
  get buckets.
- **Validation at startup**: settings that would turn a protection off are refused, such as an
  `http` issuer off loopback, a zero issuer timeout or key refresh interval, a refresh interval
  not shorter than the key cache, or cache seconds beyond 2^31 - 1 (clients read them as 32-bit
  integers).
- **Errors**: Jersey's are mapped to the `{"message": ...}` body; Jetty's own (such as `400` for
  an encoded `/` in the path) go through a JSON error handler; a client that disconnects is
  logged at debug, not as an error.
- **`HEAD .../settings`** answers from the version alone, so a servlet filter keeps
  `Content-Length` off it (RFC 9110 forbids a length other than `GET`'s).
- **Operations**: the admin port serves health checks (the datastore's `validate(Access.READ)`,
  and one per issuer: discovery and keys) and metrics, including datastore call timings and
  failures (`SettingsService.*`); keep it private.

Run it locally with `./gradlew :config-server:run --args="server config-server/config.yml"`.

## Layering in config-library

`LibraryConfigurationFactory` reads the application's `SETTINGS` set from a stack of datastores:

1. **Updates.** A datastore, usually the live one, whose values take precedence.
2. **Defaults.** A datastore read once when the factory is built. This can be an export of the live
   datastore, which gives the application a "cache load" to start from without reaching the live
   store.

A key is looked up in the updates first and falls back to the defaults. Either layer may be left
out, but not both. Lookups read in-memory copies, never a datastore, so they are cheap and never
fail because a store is down.

```java
try (LibraryConfigurationFactory factory = LibraryConfigurationFactory.builder()
    .with(Application.from("billing"))
    .with(Environment.from("prod"))
    .defaults(exportDatastore)
    .updates(liveDatastore)
    .build()) {
  Configuration configuration = factory.configuration();
}
```

- **Refresh.** A daemon thread checks the updates set's `version()` every 30 seconds
  (`refreshInterval(...)`) and reloads the set only when the version has changed. Configurations
  already handed out see the new values. `refreshInterval(Duration.ZERO)` turns the thread off;
  the application calls `refresh()` itself.
- **Updates unreachable at startup.** By default the factory serves the defaults alone, logs a
  warning, and keeps trying on each refresh (`updatesLoaded()` reports whether it has caught up).
  `requireUpdatesAtStartup(true)` makes `build()` throw instead.
- **Failures later.** A failed refresh keeps the last values read and logs once until the store
  recovers. Defaults that cannot be read fail the build.
- **Deleted updates set.** Treated as empty, so every key falls back to the defaults.
- **Lifecycle.** `ConfigurationFactory` is `AutoCloseable`; closing the library's factory stops
  the refresh thread. The caller owns and closes the datastores.

## Standards

The HTTP API (`openapi/config-api.yaml`, OpenAPI 3.1) relies on these RFCs:

| RFC | Title | Used for |
|---|---|---|
| [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) | HTTP Semantics | `GET` and `HEAD`; `ETag`, `If-None-Match` and `304 Not Modified` for version checks; `Retry-After`; status codes `400`, `404`, `500`, `503` |
| [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111) | HTTP Caching | `Cache-Control` with `private` and `max-age` |
| [RFC 5861](https://www.rfc-editor.org/rfc/rfc5861) | HTTP Cache-Control Extensions for Stale Content | `stale-while-revalidate` and `stale-if-error` |
| [RFC 6585](https://www.rfc-editor.org/rfc/rfc6585) | Additional HTTP Status Codes | `429 Too Many Requests` |
| [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) | The JSON Data Interchange Format | Response bodies |
| [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986) | URI Generic Syntax | Application, environment and key names fit in one path segment without escaping |
| [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750) | OAuth 2.0 Bearer Token Usage | `Authorization: Bearer`, the `WWW-Authenticate` challenge and `invalid_token` on `401`, TLS required |
| [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519) | JSON Web Token (JWT) | The token and its `iss`, `sub`, `aud`, `exp` and `nbf` claims |
| [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515) | JSON Web Signature (JWS) | How the token is signed |
| [RFC 7517](https://www.rfc-editor.org/rfc/rfc7517) | JSON Web Key (JWK) | The issuer's public keys (JWKS), matched by `kid` |
| [RFC 7518](https://www.rfc-editor.org/rfc/rfc7518) | JSON Web Algorithms (JWA) | Signature algorithms such as `RS256` and `ES256` |
| [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725) | JWT Best Current Practices | Refusing `none` and HMAC, checking `iss` and `aud` |
| [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068) | JWT Profile for OAuth 2.0 Access Tokens | Accepted, not required, so workload identity tokens also work |

Not an RFC: [OpenID Connect Discovery 1.0](https://openid.net/specs/openid-connect-discovery-1_0.html),
for finding an issuer's JWKS from its `iss`.

## Java compatibility

The build uses a JDK 25 toolchain and compiles every module with `--release 21`, so published
artifacts run on Java 21 and later.

## Open decisions

- Push or watch refresh for `config-library`, for backends that support it (etcd watches), in
  place of polling.
- The export format.
- Sender-constrained tokens, if bearer tokens prove not to be enough: DPoP (RFC 9449) or
  certificate-bound tokens (RFC 8705), chosen per trusted issuer.
- Whether `config-client` is hand-written or generated from the OpenAPI spec.
- Storage layout for the remaining backends (S3 uses one object per set with a deleted-set marker;
  the same approach likely suits DynamoDB and etcd), and how environments inherit or override
  values.
- Cleaning up deleted-set markers. They are 16 bytes each and keep versions monotonic, so they are
  kept for now.
- History of configuration sets (each set already has a version that only increases).
