# Security

This page summarizes the security model: how Arbiter authenticates users,
how it scopes what they can see, and how it stores secrets.

## Authentication

There are two ways to authenticate:

1. **Form login** — POST `email` and `password` to `/login`. On success, a
   session cookie is set and the user is redirected to `/`. Failure
   redirects to `/login?error`. CSRF protection is enabled for all non-API
   form posts via Spring Security's default token mechanism.

2. **Bearer API key** — send `Authorization: Bearer <api-key>` on requests
   to `/api/v1/*`. Arbiter SHA-512-hashes the incoming key and looks up the
   user by the hash. API keys carry the same role and group memberships as
   the owning user.

Both login success and login failure are recorded in the audit log
(`LOGIN`, with `outcome` either `SUCCESS` or `FAILURE`). Logout produces a
`LOGOUT` entry.

#### Session storage

Form-login sessions live in **Valkey** (Redis-protocol compatible) via
Spring Session, so multiple Arbiter replicas behind a load balancer all
read and write the same `HttpSession`. Two consequences:

- The load balancer doesn't need sticky sessions — any replica can serve
  any request.
- A signed-in user keeps their session if a replica restarts.

The connection is configured by `spring.data.redis.host` /
`spring.data.redis.port`; docker-compose ships a `valkey` service on
`6379`. Session timeout is `spring.session.timeout`, default 30 minutes.

### Multi-factor authentication (TOTP)

Arbiter supports TOTP-based MFA as a second factor for form-login sessions. API key
authentication is unaffected — keys are high-entropy secrets and are not subject to the MFA
gate.

**Per-user opt-in**: any user can enable MFA from their [Settings page](user-guide/settings.md#two-factor-authentication).
A shared secret is generated server-side, displayed as a QR code for the user to scan into an
authenticator app, and stored against their user record in the `users` collection.

**Login flow when MFA is active**:

1. Password verification succeeds but the session is not yet established.
2. The pending `Authentication` object is stored in the HTTP session under the key
   `PENDING_MFA_AUTH`; the security context is cleared.
3. The user is redirected to `/mfa`, which requires no authentication.
4. The user submits the 6-digit TOTP code. If valid, the pending authentication is promoted
   into a full Spring Security context (saved to the session via
   `HttpSessionSecurityContextRepository`) and the user proceeds to the application.
5. An invalid code returns to `/mfa` with an error; the pending authentication remains in the
   session for retry.

**Admin-enforced MFA**: administrators can turn on **Require MFA for all users** in
[Admin → Security](admin/security.md). When this policy is active, any authenticated user who
has not completed MFA enrollment is intercepted by `MfaEnrollmentInterceptor` and redirected
to `/settings/mfa/setup?required=true`. The interceptor runs on every request and releases the
user only once their `mfaEnabled` flag is `true` in the database.

MFA setup and removal are recorded in the audit log as `MFA_ENABLED` and `MFA_DISABLED`; the
admin policy change is recorded as `SECURITY_SETTINGS_CHANGE`.

## Authorization

Three roles, applied per-endpoint:

- `ROLE_USER` — default for non-admin accounts. Limited to batches in groups
  they belong to.
- `ROLE_ADMIN` — required for everything under `/admin/**`, the batch create
  and close endpoints, and for the unscoped (`myGroupsOnly=false`) view on
  the batches and queue pages.
- `ROLE_AUDITOR` — read-only counterpart to `ROLE_ADMIN`. Sees the same
  cross-group data an admin sees but cannot mutate state. Enforced by the
  `AuditorWriteRejectFilter`, which 403s any non-safe HTTP method outside a
  small self-management allow-list (`/login`, `/logout`, `/mfa`, `/settings`,
  `/invitations`). See [Roles and permissions](reference/roles.md) for the
  full feature matrix.

In addition, any `ROLE_USER` may be designated a **team lead** for one or
more groups. Team leadership is a per-group attribute stored on the group
itself (the leaders are a subset of the group's members), not a separate
role. The framework matchers therefore do not gate `/batches` to admins
alone — that endpoint is open to any authenticated user, and the per-resource
authorization happens in the controller via `BatchAccessService.canLeadBatch`,
which permits the request only if the caller is an admin or leads the
batch's group. Reassigning a batch to a different group remains admin-only.

**Group scoping** is enforced server-side regardless of the UI. Whenever a
non-admin attempts to:

- View a batch outside their groups → not in the list.
- View a document whose batch is outside their groups → returns 404 (not
  403, to avoid leaking existence).
- Modify a span / document outside their groups → 404.
- Ingest into a batch outside their groups → API returns 403.

The "Limit to my groups" toggle on the queue and batches pages is purely a
UI affordance for admins; revoking the role at the back end immediately
re-applies group restrictions.

## Unauthenticated endpoints

Three `GET` paths answer without a credential, so that a container runtime,
load balancer, or Prometheus scraper can reach Arbiter before any account
exists: `/api/health`, `/actuator/health`, and `/actuator/prometheus`. Every
other path, API and UI alike, requires authentication.

What they disclose:

- The build version (`/api/health`), which tells a caller which release is
  running and therefore which published issues apply to it.
- An aggregate health status, never the components behind it. Which
  dependency is down, and the addresses of MongoDB, Valkey, and OpenSearch,
  stay out of the response.
- JVM and HTTP request metrics. Request URIs are route templates
  (`/documents/{id}`), so no document identifier, document text, entity
  value, or user identity is exported, but request volumes and error rates
  are visible.

No other Actuator endpoint is exposed; `env`, `configprops`, `beans`,
`loggers`, and `heapdump` all return `404`, as do the `/actuator` links page
and the per-component paths under `/actuator/health/`.

Keep Arbiter's port on an internal network. Where the deployment terminates
TLS at a reverse proxy, block `/actuator` there unless your monitoring needs
it, and restrict `/api/health` to the monitors that call it if the build
version should not be visible.

## Password storage

User passwords are stored as **BCrypt** hashes (cost 12) using Spring
Security's `DelegatingPasswordEncoder`. New hashes carry the `{bcrypt}`
prefix so the encoder can route verifications without having to guess the
algorithm:

```
{bcrypt}$2a$12$<22-char-salt><31-char-hash>
```

BCrypt's 16-byte salt is generated per-password and embedded in the hash;
verification is constant-time. The cost factor (12) is the encoder's work
parameter — raising it makes both encode and verify slower, so it's the
knob to turn if you want to harden against future hardware. Change the
constructor argument to `BCryptPasswordEncoder` in the encoder bean and
existing hashes are unaffected (BCrypt verification reads the cost from the
hash itself).

Every sign-in, password change, admin reset, and invitation redemption
produces a fresh `{bcrypt}` hash. The bootstrap admin's password — printed
to standard output once on first start (see
[Getting started → First run](getting-started.md#first-run)) — is generated
from a cryptographically secure random source and encoded the same way.
It is not stored anywhere in plaintext after the start-up banner is
printed, so capture it from the start-up output and rotate it from
**Settings → Account** at first opportunity.

### Legacy SHA-512 fallback

Earlier versions of Arbiter stored passwords as salted SHA-512 in this
**unprefixed** form:

```
<saltHex>$<sha512Hex(salt + password)>
```

with a 16-byte (32-hex-char) salt. Hashes already in this form continue to
verify at login — the `DelegatingPasswordEncoder` is configured so that a
hash with no `{prefix}` is matched against the legacy encoder. The first
time the user signs in **and changes their password**, or an admin resets
it, the stored hash transitions to the `{bcrypt}` form. Plain SHA-512
hashes are never produced for new passwords.

Plain salted SHA-512 is fast (and therefore weak against offline brute
force) compared to a deliberate KDF. The fallback exists only so existing
deployments don't lock users out at the upgrade boundary; rotate everyone's
password to migrate them off the legacy scheme.

## API key storage

API keys are 256 bits of cryptographic randomness, base64url-encoded. On
generation Arbiter:

1. Stores `sha512(key)` (no salt — every key is high-entropy by design).
2. Returns the plaintext key to the user **once**, in a banner on the
   settings page.

Subsequent authentication SHA-512-hashes the incoming bearer token and
looks up the user record by that hash. Keys cannot be recovered from
the database; if a user loses theirs they generate a new one (which
replaces the old).

## SMTP credentials

The notification settings include an SMTP password used to talk to a mail
relay. That password is stored as-is in the `settings` collection so
Arbiter can present it at send time. Treat the database as sensitive
storage — encrypt at rest at the storage layer or front it with a secret
manager if your deployment policy demands it.

## Philter API keys

Each Philter instance configured under **Admin → Philter** can carry an
optional API key that Arbiter sends as `Authorization: Bearer …` on every
outbound call (and on the per-row Test). The plaintext key is **encrypted
with AES-GCM** before being written to MongoDB:

- 32-byte AES key loaded from the `arbiter.crypto.secret` property. The
  property must be a **base64-encoded value of exactly 32 random bytes**
  — generate one with `openssl rand -base64 32` (or
  `head -c 32 /dev/urandom | base64`).
- 12-byte random IV per encryption; ciphertext + GCM auth tag stored as
  base64.

Anything else — an unset property, a passphrase, base64 of the wrong
length — fails fast at startup with a descriptive error. There is no
silent fallback to a public dev key, and no SHA-256-of-passphrase
derivation: a leaked database paired with a low-entropy passphrase used
to be brute-forceable in seconds, so passphrase-style secrets are no
longer accepted. The plaintext key is never displayed back; the admin UI
only shows whether a key is configured.

## Data source credentials

Credentials configured under **Admin → Data Sources** — OpenSearch passwords,
S3 access and secret keys, and relational-database usernames and passwords —
are stored using the same AES-GCM encryption scheme described above for
Philter API keys. The plaintext is never returned by the UI or logged in the
audit trail; the listing tables show only a status (`Configured`, `Ambient`,
`From URL`, `••••••`). The OpenSearch **username** field is the one
exception: it is stored as plaintext on the document row and visible in raw
Mongo documents. Local-directory sources have no credentials at all — files
are read with the application's process identity. See
[Data sources](admin/data-sources.md) for the per-type field list.

## PII at rest in MongoDB

Document text and the individual PII spans detected inside it are **encrypted
on disk** in the MongoDB collections that hold them. The encryption uses the
same AES-GCM construction described above for Philter API keys, with the same
key (`arbiter.crypto.secret`) — there is no separate per-collection key.

The fields encrypted at rest are:

| Collection         | Field            | What it carries                                                       |
|--------------------|------------------|-----------------------------------------------------------------------|
| `documents`        | `originalText`   | Source document text being redacted — the raw PII.                    |
| `documents`        | `redactedText`   | Rendered redacted output persisted at finalize time.                  |
| `documents`        | `failureMessage` | Error messages may quote spans of the source document.                |
| `spans`            | `text`           | The literal PII string detected by Philter (e.g. `alice@example.com`).|
| `document_comments`| `text`           | Reviewer-supplied free text that may reference PII.                   |

Other fields on these documents (status, timestamps, source attribution,
hashes, lock metadata, span coordinates, reviewer attribution) are stored
unchanged. Encrypting only the body fields keeps the database queryable for
status filters, joins, and audits.

### How the encryption is applied

Encryption and decryption happen at the persistence layer through Spring Data
Mongo lifecycle callbacks — application code reads and writes plaintext on
the in-memory entity, and the callbacks transparently encrypt before save and
decrypt after load. The on-disk form of an encrypted field carries an
`enc:v1:` prefix marker followed by the base64-encoded ciphertext (12-byte
random IV concatenated with the GCM ciphertext + 16-byte auth tag), e.g.
`enc:v1:bXlJVi4uLg==`.

### Backwards compatibility

The marker prefix lets the read path tell encrypted values from values that
predate the rollout. Any field without the prefix is returned to the
application as-is, so a database that was populated before this feature was
turned on continues to read normally. The first save of any such row
re-writes the affected field as ciphertext. There is no offline migration —
encryption rolls forward as documents are touched.

### What is **not** encrypted

The following fields are **not** part of the at-rest encryption boundary:

- **Span location** (`location.characterStart`, `location.characterEnd`,
  page, coordinates). The offsets reveal the position of redactions inside
  the document but contain no PII themselves; keeping them queryable lets
  the review UI render the highlighted regions without an extra decrypt.
- **Reviewer attribution** (`statusChangedBy`, `userEmail`, `approvedBy`,
  audit-log actors). An admin investigation needs these in plaintext to
  attribute decisions; they are not PII for our purposes.
- **OpenSearch search index**. When OpenSearch indexing is configured the
  document content is also written to OpenSearch — that store is **outside**
  the at-rest encryption boundary documented here. If your deployment
  indexes documents into OpenSearch, configure encryption-at-rest on the
  OpenSearch cluster as well; the application does not double-encrypt
  payloads sent over the indexing path.

### Key management

The same `arbiter.crypto.secret` that protects credentials and API keys also
protects PII at rest. Losing the key means **losing access to every
encrypted field** — back it up at the same operational tier as your database
backups. Rotating the key requires re-encrypting every existing row; this is
intentionally out-of-band today (no UI affordance) so that a sloppy rotation
does not leave half the corpus unreadable.

## PII never appears in application logs

Arbiter's logging policy is that **document content is never written to a log
file or to standard out**, regardless of log level. The fields covered are
the same ones encrypted at rest in MongoDB:

- Document `originalText` (the source text being redacted) and
  `redactedText` (the rendered output).
- Document `failureMessage` text.
- Span `text` (the literal PII string detected).
- Comment `text`.
- User-supplied search queries (a reviewer searching for an email or phone
  number must not pin that string in the log file).

Network call sites that interact with stores carrying this content
(OpenSearch indexing, OpenSearch search, OpenSearch `more_like_this`,
OpenSearch and Elasticsearch ingest) deliberately log only metadata —
status code, body length, document id, batch id, error class — when they
fail. Response bodies and query strings are summarised by length, never
echoed verbatim. Exceptions thrown from those layers carry the same
metadata-only message so a downstream `log.warn(..., e.getMessage())` does
not regress this contract.

If a richer dump is needed for diagnosis, read the **OpenSearch /
Elasticsearch cluster's own logs** rather than Arbiter's — those stores
have their own access controls and are part of the same security boundary
documented above. The bootstrap admin notice on first start is the only
deliberate write to standard out, and it carries no document PII — just
the configured admin email and a generated initial password.

## Document content integrity

Every document Arbiter ingests — whether through the web upload form or
through `POST /api/v1/ingest` — has its raw content **SHA-512 hashed** at
ingest time and the hash recorded on the document row in the `documents`
collection (field `contentSha512`, lowercase hex). The hash is computed from
the bytes you submitted: UTF-8 bytes for text uploads, the raw file bytes
for PDFs.

The hash is set once at ingest and is never overwritten thereafter. Because
the per-document audit log includes the ingest event with its timestamp and
the actor, you can pair the two to attest "user X ingested a document with
this exact content at this time" — useful for chain-of-custody, deduplication,
and tamper detection against a separately-archived original.

The hash is *not* surfaced in the UI today; query MongoDB directly if you
need it for an out-of-band reconciliation.

## CSRF and CORS

CSRF protection is enabled for HTML form posts and disabled for the
`/api/**` paths. The API surface is split into two categories:

- **Browser-UI endpoints** (`/api/v1/queue`, `/api/v1/batches`,
  `/api/v1/documents/{id}/spans|comments|history|certificate|explain`,
  `/api/v1/spans/**`, `/api/v1/policies/**`, `/api/v1/ollama/**/models`,
  `/api/v1/review/**`) accept the same session cookie the surrounding HTML
  page uses. The in-page JavaScript calls them with `fetch`. Cross-origin
  abuse is prevented by the browser's same-origin policy plus the absence
  of a permissive CORS configuration — Arbiter does not expose
  `Access-Control-Allow-Origin` for any other origin, so a malicious page
  cannot read responses from these endpoints even if it happened to send
  the user's session cookie.
- **Programmatic-only endpoints** (`/api/v1/ingest`, `/api/v1/search`,
  `POST /api/v1/documents/{id}/finalize`, `GET /api/v1/documents/{id}/audit`)
  are Bearer-only: the `ApiSessionRejectingFilter` drops session-cookie
  authentication for these paths so they can only be reached with an
  `Authorization: Bearer <api-key>` header. This is the canonical defense
  against a logged-in admin being CSRF-pushed into ingesting fake content
  or exporting redacted data through their browser.

There is no CORS configuration shipped — if you front Arbiter with a
different origin, configure CORS in your reverse proxy or extend
`SecurityConfig`.

## Audit visibility

Everything that changes server-side state is captured in the
[audit log](admin/audit-log.md) with the actor's email, IP address (honoring
`X-Forwarded-For` first hop), the resource type and id, and per-action
context. Audit writes never include passwords, API keys, or SMTP passwords.
