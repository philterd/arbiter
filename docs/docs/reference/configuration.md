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

### `server.forward-headers-strategy=framework`

Tells Arbiter to trust the `X-Forwarded-*` headers added by a TLS-terminating
reverse proxy (nginx, AWS ALB, etc.) so it knows the request originally
arrived over HTTPS. Without this, the session cookie would be marked
"insecure" because Arbiter would think the connection was plain HTTP. The
`framework` value is safe behind a single well-controlled proxy. If you
terminate TLS in Arbiter itself, set this to `none`.

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
  because `forward-headers-strategy=framework` (above) makes Arbiter believe
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

## MongoDB

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
