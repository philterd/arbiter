# Configuration

Arbiter reads its configuration from `application.properties` (or matching
environment variables). The defaults shipped in the file are intended for the
bundled `docker-compose.yaml` deployment; override any property by editing
the file, by setting an environment variable named the same way (e.g.
`SERVER_PORT`), or by pointing Arbiter at an additional properties file with
the `--spring.config.additional-location=...` command-line argument.

## HTTP server

### `server.port`

The port Arbiter listens on. Defaults to `8080`.

### `server.forward-headers-strategy=native`

Tells Arbiter to use the `X-Forwarded-For` and `X-Forwarded-Proto` headers
added by a TLS-terminating reverse proxy (nginx, AWS ALB, etc.), so it knows
the client's address and that the request arrived over HTTPS. Without this,
the session cookie would be marked "insecure" because Arbiter would think the
connection was plain HTTP.

Arbiter believes these headers only on requests from an address matching
`server.tomcat.remoteip.internal-proxies`, set with the environment variable
`ARBITER_TRUSTED_PROXIES_REGEX`. The default matches every private-network and
loopback address, so any client on a private network that reaches Arbiter
directly can set its own address. Set `ARBITER_TRUSTED_PROXIES_REGEX` to a
regular expression matching only your reverse proxy's address. If you
terminate TLS in Arbiter itself, set the strategy to `none`.

### Session cookie hardening

```
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.same-site=strict
```

- **`http-only=true`** — JavaScript cannot read the session cookie, which
  blunts cookie theft via cross-site scripting.
- **`secure=true`** — the browser only sends the cookie over HTTPS. Works
  correctly even when Arbiter receives plain HTTP from a reverse proxy
  because `forward-headers-strategy=native` (above) makes Arbiter believe
  the request arrived over HTTPS.
- **`same-site=strict`** — the browser will not send the cookie on any
  cross-site request, which closes the cross-site request forgery vector
  that a missing `SameSite` attribute would otherwise leave open.

## Upload limits

```
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=110MB
```

These are the hard ceilings enforced before a request reaches Arbiter's
business logic. The runtime per-request upload limit lives under
**Admin → General**; that limit is the one operators normally adjust. The
ceiling here only kicks in if an admin raises the runtime limit above 100 MB.

## Database

### `arbiter.store`

Which database Arbiter uses: `mongodb` (the default) or `sqlite`. The SQLite
settings (`arbiter.sqlite.path`, `arbiter.sqlite.pool-size`,
`arbiter.sqlite.busy-timeout-ms`, `arbiter.sqlite.synchronous`) are described
in [Database](database.md#sqlite). The settings below apply to MongoDB.

### `spring.data.mongodb.uri`

The connection URI to MongoDB. Default: `mongodb://localhost:27017/arbiter`.

### `spring.data.mongodb.auto-index-creation=true`

Auto-create the indexes Arbiter needs (unique-name constraints on users and
batches, "one in-flight import per batch", etc.) the first time each
collection is used. Leave this `true` unless you create the indexes through
another mechanism — without them, duplicate-key checks won't be enforced and
the import dispatcher won't behave correctly.

## Sessions and Valkey

```
spring.session.store-type=redis
spring.session.timeout=30m
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

Session state lives in [Valkey](https://valkey.io/) (a Redis
protocol-compatible server) so user sessions are shared across application
replicas. The bundled `docker-compose.yaml` overrides
`spring.data.redis.host` via environment variable so the app talks to the
Valkey container.

When Valkey is not reachable on local non-Docker runs, comment out
`spring.session.store-type` to fall back to in-memory sessions. That is fine
for single-instance development, but sessions are lost on restart and won't
work in a multi-replica deployment.

`spring.session.timeout` controls how long an idle session stays valid
before the user is logged out.

## Sign-in

People sign in with Arbiter's own accounts (`local`, the default) or through
Philter 4.0's user management API (`philter`). See
[Sign-in through Philter](../security.md#sign-in-through-philter).

| Property | Environment variable | Purpose |
| -------- | -------------------- | ------- |
| `arbiter.sign-in.mode` | `ARBITER_SIGN_IN_MODE` | `local` (default) or `philter`. Any other value stops Arbiter at startup. |
| `arbiter.sign-in.philter-url` | `ARBITER_SIGN_IN_PHILTER_URL` | Required in `philter` mode: the `http` or `https` URL of the Philter that signs people in, for example `https://philter:8080`. |

Both are read only at startup, so an administrator signed in to Arbiter cannot
change how people sign in. Calls to this Philter use the global
`arbiter.philter.tls.*` settings in
[TLS and client certificates](#tls-and-client-certificates); with Philter's
Docker image, which serves HTTPS with a self-signed certificate, set
`arbiter.philter.tls.trust-store` to a trust store holding that certificate.

The Philter must run with `PASSWORD_SIGN_IN_ENABLED=true`. Otherwise every
sign-in fails with "Sign-in is unavailable" and Arbiter logs a warning naming
the setting. List Arbiter's address in Philter's `TRUSTED_PROXIES`, so Philter
rate limits and records each person's own address rather than Arbiter's.

Arbiter sends Philter the address it believes the person has. With the default
`ARBITER_TRUSTED_PROXIES_REGEX` (see
[`server.forward-headers-strategy=native`](#serverforward-headers-strategynative)),
a client on a private network that reaches Arbiter directly can set that address,
and so choose the address Philter rate limits. Narrow the setting to your reverse
proxy. Philter's lockout per username does not depend on the address.

In `philter` mode `ARBITER_ADMIN_INITIAL_PASSWORD` is not needed and no local
administrator is created. Switching back to `local` requires it again: when
no administrator has a local password, Arbiter seeds `admin@philterd.ai` with
it at startup. People who signed in through Philter cannot sign in locally,
since Arbiter holds no password for them.

## Threading

### `spring.threads.virtual.enabled=true`

Run request handling on lightweight threads so Arbiter handles many
simultaneous reviewers more efficiently. Recommended; turn off only if you
hit a compatibility issue.

## Demo data

```
arbiter.demo-data.enabled=true
arbiter.demo-data.directory=/app/sample-files
```

When enabled and Arbiter starts up against an empty database, a small set of
sample documents is loaded and run through redaction so the UI has something
to demonstrate. This runs exactly once on a fresh install — once any document
exists, the loader does nothing.

The default directory is the path mounted by `docker-compose.yaml`. When that
directory does not exist (running locally outside Docker), the loader falls
back to `./sample-files` and `../sample-files` relative to the working
directory.

Set `arbiter.demo-data.enabled=false` for production deployments.

## Monitoring and metrics

```
management.endpoints.web.exposure.include=health,prometheus
management.endpoints.web.discovery.enabled=false
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
management.health.redis.enabled=${MANAGEMENT_HEALTH_REDIS_ENABLED:false}
```

Arbiter serves `/api/health`, `/actuator/health`, and `/actuator/prometheus`,
all unauthenticated and `GET`-only. See
[REST API → Health and metrics](api.md#health-and-metrics) for the response
shapes and what they disclose.

The exposure list is a security control, not a convenience. Both exposed
endpoints are reachable without a credential, so adding `env`, `configprops`,
`beans`, `loggers`, or `heapdump` to it would hand configuration or memory
contents to anything that can reach the port. Everything not on the list
returns `404`, and the `/actuator` links page is switched off as well.

`show-details` and `show-components` are pinned off so an unauthenticated
caller gets the aggregate status and never learns which dependency is down or
what address it sits at.

MongoDB, Valkey, disk space, and (when full text search is enabled) OpenSearch
contribute to the aggregate. Nothing needs to be configured for them. Note that
an unreachable MongoDB delays the health response by the driver's
server-selection timeout; see
[REST API → Health and metrics](api.md#health-and-metrics).

Valkey counts because Arbiter stores sessions in it and cannot serve a page
without it, so an unreachable Valkey is a genuine outage rather than a
degradation. `MANAGEMENT_HEALTH_REDIS_ENABLED=false` drops it from health if
your deployment has some other reason to leave it out.

## Philter integration

Philter instances are managed at runtime under **Admin → Philter** rather than
through configuration. When no default instance is configured, Arbiter falls
back to its bundled redaction engine. There is nothing to set in
`application.properties` to enable or disable this — it is driven entirely by
the admin UI.

### TLS and client certificates

When an instance's endpoint starts with `https://`, Arbiter verifies Philter's
certificate, including that it names the endpoint's host. Two optional
settings apply to every Philter instance that does not have its own (see
[Per-instance client certificates and trust stores](#per-instance-client-certificates-and-trust-stores)):

| Property | Purpose |
| -------- | ------- |
| `arbiter.philter.tls.trust-store` | A PKCS12 or JKS trust store holding the certificate authority (or self-signed certificate) to trust for Philter, in place of the JVM's default trust store. |
| `arbiter.philter.tls.trust-store-password` | The trust store's password, if it has one. |
| `arbiter.philter.tls.key-store` | A PKCS12 or JKS keystore holding a client certificate and its private key, presented to Philter for mutual TLS. |
| `arbiter.philter.tls.key-store-password` | The keystore's password, which must also be the private key's password. Leave it empty only for a keystore without one; a missing or wrong password stops Arbiter at startup. |

Set the passwords through environment variables (for example
`ARBITER_PHILTER_TLS_KEY_STORE_PASSWORD`) or a secrets mechanism rather than
in `application.properties`. Mount the store files read-only.

Arbiter reads the stores when it starts and refuses to start if a file is
missing, can't be opened with its password, or holds no usable key or
certificate. The error names the file, never the password. Build a trust store
with `keytool -importcert`; a PKCS12 trust store made with `openssl pkcs12`
reads as empty in Java.

Mutual TLS is enforced on the Philter side, by Philter or by a TLS proxy in
front of it that requires client certificates. To have Philter require them
itself, see [Client certificates (mutual TLS)](https://philterd.github.io/philter/latest/settings.html#client-certificates-mutual-tls)
in Philter's settings. A client certificate does not replace Arbiter's Philter
API key: Philter still requires the key on every request.

### Per-instance client certificates and trust stores

An instance can carry its own client certificate keystore, trust store, or
both, attached under **Admin → Philter** (see
[Registering an external Philter](../workflow.md#registering-an-external-philter-admin)).
Use this when instances need different certificates, for example a Philter in
another enclave behind a proxy that trusts a different certificate authority.

Each store falls back on its own: an instance with only its own trust store
still presents the global client certificate from
`arbiter.philter.tls.key-store`, and an instance with only its own client
certificate still trusts `arbiter.philter.tls.trust-store` (or the JVM's
default trust store). An instance with neither uses the global settings.

The stores and their passwords are encrypted with `arbiter.crypto.secret`
before they are saved and are never shown again. Arbiter validates a store
when it is attached, with the same checks as the global settings, and rejects
it with a message naming the problem, never the password. If an attached store
later can't be decrypted, for example after `arbiter.crypto.secret` changes,
calls to that instance fail with an error naming the instance rather than
falling back to the global settings. Attach the stores again to fix it.

## Data-source allow-list

```
arbiter.data-sources.allowed-hosts=
```

Optional comma-separated list of host patterns that Arbiter accepts as the
target of an outbound data-source connection (OpenSearch / Elasticsearch
test endpoints, ingest workers, Philter, Ollama, JDBC URLs). Patterns may be
exact hostnames (`opensearch.example.com`) or use a leading wildcard
(`*.search.example.com`).

When unset, public hosts are allowed and private/internal addresses
(loopback, link-local, private network ranges) are blocked. When set,
**every** outbound host must match a pattern. The same check applies at
data-source create time and again at the moment Arbiter actually makes the
outbound call, so changing the list takes effect on the next request — no
restart needed.
