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
