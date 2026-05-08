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

Two roles, applied per-endpoint:

- `ROLE_USER` — default for non-admin accounts. Limited to batches in groups
  they belong to.
- `ROLE_ADMIN` — required for everything under `/admin/**`, the batch create
  and close endpoints, and for the unscoped (`myGroupsOnly=false`) view on
  the batches and queue pages.

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

## Password storage

User passwords are stored as **salted SHA-512** hashes. The format is:

```
<saltHex>$<sha512Hex(salt + password)>
```

The salt is 16 random bytes (32 hex characters) generated per-password with
`SecureRandom`. Verification is constant-time. The seeded admin's default
password (`admin`) is hashed with this scheme on first run and **must** be
rotated immediately after first sign-in.

Plain SHA-512 with a salt is faster (and therefore weaker against brute
force) than a deliberate KDF like bcrypt or argon2. For higher security
you may want to swap the encoder for a PBKDF2/argon2/bcrypt
implementation; the encoder bean is the only thing that needs to change.

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

- 32-byte AES key derived from the `arbiter.crypto.secret` property
  (accepts a base64-encoded 32-byte key directly, or any passphrase, in
  which case Arbiter SHA-256-derives 32 bytes from it).
- 12-byte random IV per encryption; ciphertext + GCM auth tag stored as
  base64.

If `arbiter.crypto.secret` is unset, Arbiter falls back to an insecure
deterministic dev key with a logged warning — set the property in any
non-development deployment. The plaintext key is never displayed back; the
admin UI only shows whether a key is configured.

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

CSRF protection is enabled for HTML form posts and disabled only for the
`/api/**` paths (which are protected by Bearer authentication). There is no
CORS configuration shipped — if you front Arbiter with a different origin,
configure CORS in your reverse proxy or extend `SecurityConfig`.

## Audit visibility

Everything that changes server-side state is captured in the
[audit log](admin/audit-log.md) with the actor's email, IP address (honoring
`X-Forwarded-For` first hop), the resource type and id, and per-action
context. Audit writes never include passwords, API keys, or SMTP passwords.
