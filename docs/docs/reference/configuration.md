# Configuration

Philter Workspace reads its configuration from `application.properties` (or matching
environment variables). The defaults shipped in the file are intended for the
bundled `docker-compose.yaml` deployment; override any property by editing
the file, by setting an environment variable named the same way (e.g.
`SERVER_PORT`), or by pointing Philter Workspace at an additional properties file with
the `--spring.config.additional-location=...` command-line argument.

## HTTP server

### `server.port`

The port Philter Workspace listens on. Defaults to `8080`.

### `server.forward-headers-strategy=native`

Tells Philter Workspace to use the `X-Forwarded-For` and `X-Forwarded-Proto` headers
added by a TLS-terminating reverse proxy (nginx, AWS ALB, etc.), so it knows
the client's address and that the request arrived over HTTPS. Without this,
the session cookie would be marked "insecure" because Philter Workspace would think the
connection was plain HTTP.

Philter Workspace believes these headers only on requests from an address matching
`server.tomcat.remoteip.internal-proxies`, set with the environment variable
`PHILTER_WORKSPACE_TRUSTED_PROXIES_REGEX`. The default matches every private-network and
loopback address, so any client on a private network that reaches Philter Workspace
directly can set its own address. Set `PHILTER_WORKSPACE_TRUSTED_PROXIES_REGEX` to a
regular expression matching only your reverse proxy's address. If you
terminate TLS in Philter Workspace itself, set the strategy to `none`.

### Session cookie hardening

```
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.same-site=strict
```

- **`http-only=true`** — JavaScript cannot read the session cookie, which
  blunts cookie theft via cross-site scripting.
- **`secure=true`** — the browser only sends the cookie over HTTPS. Works
  correctly even when Philter Workspace receives plain HTTP from a reverse proxy
  because `forward-headers-strategy=native` (above) makes Philter Workspace believe
  the request arrived over HTTPS.
- **`same-site=strict`** — the browser will not send the cookie on any
  cross-site request, which closes the cross-site request forgery vector
  that a missing `SameSite` attribute would otherwise leave open.

## Upload limits

```
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=110MB
```

These are the hard ceilings enforced before a request reaches Philter Workspace's
business logic. The runtime per-request upload limit lives under
**Admin → General**; that limit is the one operators normally adjust. The
ceiling here only kicks in if an admin raises the runtime limit above 100 MB.

## Database

### `philter-workspace.store`

Which database Philter Workspace uses: `mongodb` (the default) or `sqlite`. The SQLite
settings (`philter-workspace.sqlite.path`, `philter-workspace.sqlite.pool-size`,
`philter-workspace.sqlite.busy-timeout-ms`, `philter-workspace.sqlite.synchronous`) are described
in [Database](database.md#sqlite). The settings below apply to MongoDB.

### `spring.data.mongodb.uri`

The connection URI to MongoDB. Default: `mongodb://localhost:27017/philter-workspace`.

### `spring.data.mongodb.auto-index-creation=true`

Auto-create the indexes Philter Workspace needs (unique-name constraints on users and
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

People sign in through the user management API of Philter 4.x (see
[Supported Philter versions](../workflow.md#supported-philter-versions)), at Philter Workspace's Philter,
`PHILTER_WORKSPACE_PHILTER_URL` (see [Philter integration](#philter-integration)). See
[Sign-in through Philter](../security.md#sign-in-through-philter).

| Property | Environment variable | Purpose |
| -------- | -------------------- | ------- |
| `philter-workspace.sign-in.check-interval-seconds` | `PHILTER_WORKSPACE_SIGN_IN_CHECK_INTERVAL_SECONDS` | How often Philter Workspace checks a person's session key with Philter while they use it, in seconds (default `60`). `0` checks every request. A session Philter has ended, or whose role has changed, ends at the next check. The check is what keeps Philter's idle timeout for the key moving, so keep the interval well under Philter's `SESSION_KEY_IDLE_TIMEOUT_MINUTES` (30 by default), or people are signed out while they work. A negative value stops Philter Workspace at startup. |
| `philter-workspace.api-keys.cache-seconds` | `PHILTER_WORKSPACE_API_KEYS_CACHE_SECONDS` | How long Philter Workspace keeps Philter's answer for a [Philter API key](../security.md#api-keys) used on its API, in seconds (default `30`). A revoked key, a deactivation, or a role change applies within this time. `0` asks Philter on every request. A negative value stops Philter Workspace at startup. |

`PHILTER_WORKSPACE_SIGN_IN_MODE` is no longer read: Philter Workspace refuses to start with `local`, which was removed
(see [Upgrading from local sign-in](../security.md#upgrading-from-local-sign-in)).
These settings are read only at startup, so an administrator signed in to Philter Workspace
cannot change how people sign in.
Calls to Philter use the
`philter-workspace.philter.tls.*` settings in
[TLS and client certificates](#tls-and-client-certificates); with Philter's
Docker image, which serves HTTPS with a self-signed certificate, set
`philter-workspace.philter.tls.trust-store` to a trust store holding that certificate.

The Philter must run with `PASSWORD_SIGN_IN_ENABLED=true`. Otherwise every
sign-in fails with "Sign-in is unavailable" and Philter Workspace logs a warning naming
the setting. List Philter Workspace's address in Philter's `TRUSTED_PROXIES`, so Philter
rate limits and records each person's own address rather than Philter Workspace's.

Philter Workspace sends Philter the address it believes the person has. With the default
`PHILTER_WORKSPACE_TRUSTED_PROXIES_REGEX` (see
[`server.forward-headers-strategy=native`](#serverforward-headers-strategynative)),
a client on a private network that reaches Philter Workspace directly can set that address,
and so choose the address Philter rate limits. Narrow the setting to your reverse
proxy. Philter's lockout per username does not depend on the address.

## Threading

### `spring.threads.virtual.enabled=true`

Run request handling on lightweight threads so Philter Workspace handles many
simultaneous reviewers more efficiently. Recommended; turn off only if you
hit a compatibility issue.

## Demo data

```
philter-workspace.demo-data.enabled=true
philter-workspace.demo-data.directory=/app/sample-files
```

When enabled and Philter Workspace starts up against an empty database, a small set of
sample documents is loaded and run through redaction so the UI has something
to demonstrate. This runs exactly once on a fresh install — once any document
exists, the loader does nothing.

The default directory is the path mounted by `docker-compose.yaml`. When that
directory does not exist (running locally outside Docker), the loader falls
back to `./sample-files` and `../sample-files` relative to the working
directory.

Set `philter-workspace.demo-data.enabled=false` for production deployments.

## Batch defaults

```
philter-workspace.batch.defaults.pii-threshold=0.80
philter-workspace.batch.defaults.document-threshold=0.25
philter-workspace.batch.defaults.audit-sampling-rate=0.10
philter-workspace.batch.minimum-audit-sampling-rate=
```

The values a new batch starts with, and the values that fill the
[create dialog](../admin/batches.md#create-a-batch-admin-only). Each can also be
set as an environment variable, for example
`PHILTER_WORKSPACE_BATCH_DEFAULTS_AUDIT_SAMPLING_RATE`. Unset, they are `0.80`,
`0.25`, and `0.10`. Each must be from 0 to 1, or Philter Workspace refuses to
start. A new default applies to batches created afterwards; existing batches
keep their values. The demo data batch also uses them.

The audit sampling rate decides what happens to a document with nothing left to
decide, because every detection was at or above the PII threshold. At `0.10`,
about one in ten of those documents goes to a person as `AUDIT_REQUIRED` and the
rest are `AUTO_APPROVED` without anyone opening them. At `1.0`, every one goes to
a person, so a person approves every document.

`philter-workspace.batch.minimum-audit-sampling-rate`
(`PHILTER_WORKSPACE_BATCH_MINIMUM_AUDIT_SAMPLING_RATE`) is the lowest rate a batch
can be given. With it set, a lower rate is refused when a batch is created or its
settings are saved, and the attempt is recorded in the audit log as
`BATCH_AUDIT_SAMPLING_RATE_REFUSED`. A deployment that requires a person to
approve every document sets it to `1.0`; with no default rate configured, new
batches then start at `1.0` too. A default below the minimum stops startup.
Batches saved before the minimum was set keep their rate; their settings can
be saved again only with a rate at or above the minimum.

## Monitoring and metrics

```
management.endpoints.web.exposure.include=health,prometheus
management.endpoints.web.discovery.enabled=false
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
management.health.redis.enabled=${MANAGEMENT_HEALTH_REDIS_ENABLED:false}
```

Philter Workspace serves `/api/health`, `/actuator/health`, and `/actuator/prometheus`,
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

Valkey counts because Philter Workspace stores sessions in it and cannot serve a page
without it, so an unreachable Valkey is a genuine outage rather than a
degradation. `MANAGEMENT_HEALTH_REDIS_ENABLED=false` drops it from health if
your deployment has some other reason to leave it out.

## Philter integration

Philter Workspace works with one Philter, which signs people in, holds the policies, and
redacts. See [Configure Philter](../workflow.md#1-configure-philter).

| Property | Environment variable | Purpose |
| -------- | -------------------- | ------- |
| `philter-workspace.philter.url` | `PHILTER_WORKSPACE_PHILTER_URL` | Required: Philter's `http` or `https` URL, for example `http://philter:8080`. A URL holding a user name or password stops Philter Workspace at startup. |
| `philter-workspace.philter.api-key` | `PHILTER_WORKSPACE_PHILTER_API_KEY` | Required: the API key Philter Workspace uses for background work such as ingest, for a Philter user who is not an administrator. See [Philter API key for Philter Workspace](../workflow.md#philter-api-key-for-philter-workspace). Set it through the environment or a secrets mechanism, not `application.properties`. |

Both are read only at startup, and Philter Workspace does not start without them. It logs
the URL it uses, never the key. **Admin → Philter** shows the URL and tests the
connection, but cannot change either setting.

### TLS and client certificates

When `PHILTER_WORKSPACE_PHILTER_URL` starts with `https://`, Philter Workspace verifies Philter's
certificate, including that it names the URL's host. Two optional settings
apply:

| Property | Purpose |
| -------- | ------- |
| `philter-workspace.philter.tls.trust-store` | A PKCS12 or JKS trust store holding the certificate authority (or self-signed certificate) to trust for Philter, in place of the JVM's default trust store. |
| `philter-workspace.philter.tls.trust-store-password` | The trust store's password, if it has one. |
| `philter-workspace.philter.tls.key-store` | A PKCS12 or JKS keystore holding a client certificate and its private key, presented to Philter for mutual TLS. |
| `philter-workspace.philter.tls.key-store-password` | The keystore's password, which must also be the private key's password. Leave it empty only for a keystore without one; a missing or wrong password stops Philter Workspace at startup. |

Set the passwords through environment variables (for example
`PHILTER_WORKSPACE_PHILTER_TLS_KEY_STORE_PASSWORD`) or a secrets mechanism rather than
in `application.properties`. Mount the store files read-only.

Philter Workspace reads the stores when it starts and refuses to start if a file is
missing, can't be opened with its password, or holds no usable key or
certificate. The error names the file, never the password. Build a trust store
with `keytool -importcert`; a PKCS12 trust store made with `openssl pkcs12`
reads as empty in Java.

Mutual TLS is enforced on the Philter side, by Philter or by a TLS proxy in
front of it that requires client certificates. To have Philter require them
itself, see [Client certificates (mutual TLS)](https://philterd.github.io/philter/latest/settings.html#client-certificates-mutual-tls)
in Philter's settings. A client certificate does not replace Philter Workspace's Philter
API key: Philter still requires the key on every request.

### Philter webhook

Philter calls a webhook when a job queued with Philter Workspace's API key finishes,
so Philter Workspace does not have to ask. Philter Workspace queues no jobs in Philter
yet; the webhook is ready for the PDF review that will.

| Property | Environment variable | Purpose |
| -------- | -------------------- | ------- |
| `philter-workspace.philter.webhook-secret` | `PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET` | The shared secret Philter signs each delivery with. At least 16 characters; generate one with `openssl rand -hex 24`. Without it, Philter Workspace receives no webhooks. |
| `philter-workspace.philter.webhook-url` | `PHILTER_WORKSPACE_PHILTER_WEBHOOK_URL` | The URL Philter calls: Philter Workspace's address as Philter reaches it, followed by `/api/philter/webhook`, for example `http://philter-workspace:8080/api/philter/webhook`. Needed to register the webhook. |

A secret shorter than 16 characters, or a URL that is not `http` or `https`, stops
Philter Workspace at startup. Set the secret through the environment or a secrets
mechanism.

To set it up:

1. Set both in Philter Workspace's environment and restart it.
2. Give `PHILTER_WORKSPACE_PHILTER_API_KEY` the `webhooks:read` and `webhooks:write`
   scopes in Philter, since the webhook belongs to that key's user.
3. When Philter Workspace is on a private network, as in Docker Compose, allow it in
   Philter's webhook allowlist (**Admin → Philter → Settings**): list both Philter
   Workspace's host name and its address or range, such as the Docker network's
   range. Philter refuses a private address that is not listed, even when its host
   name is.
4. Under **Admin → Philter**, choose **Register with Philter**, then **Send a test
   event**.

Philter Workspace accepts a delivery only when its `X-Philter-Signature` is the
HMAC-SHA256 of its timestamp and body with the secret, and its timestamp is within
five minutes. Anything else is refused with `401` and logged with the delivery's id
and the reason, never its body. A delivery Philter retries is recognized by its
`X-Philter-Delivery-Id` and acknowledged without being handled again. Events Philter
Workspace does not handle are acknowledged, as Philter asks.

## Data-source allow-list

```
philter-workspace.data-sources.allowed-hosts=
```

Optional comma-separated list of host patterns that Philter Workspace accepts as the
target of an outbound data-source connection (OpenSearch / Elasticsearch
test endpoints, ingest workers, Ollama, JDBC URLs). Philter's URL is an
environment setting rather than an admin one, so it is not checked against
this list. Patterns may be
exact hostnames (`opensearch.example.com`) or use a leading wildcard
(`*.search.example.com`).

When unset, public hosts are allowed and private/internal addresses
(loopback, link-local, private network ranges) are blocked. When set,
**every** outbound host must match a pattern. The same check applies at
data-source create time and again at the moment Philter Workspace actually makes the
outbound call, so changing the list takes effect on the next request — no
restart needed.
