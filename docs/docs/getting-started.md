# Getting started

## Prerequisites

- A running MongoDB instance Arbiter can reach
- A running OpenSearch instance Arbiter can reach (for full-text search)
- A running Philter / Phileas redaction service Arbiter can call (the
  built-in redaction engine works out of the box for development)

## Configuration

Arbiter reads its settings from `application.properties` (or matching
environment variables); see [Configuration](reference/configuration.md) for the
full reference. The settings most operators need to set are:

| Property                                  | Purpose                                                |
| ----------------------------------------- | ------------------------------------------------------ |
| `spring.data.mongodb.uri`                 | Connection URI for MongoDB                             |
| `spring.data.mongodb.database`            | Database name (default: `arbiter`)                     |
| `arbiter.opensearch.endpoint`             | OpenSearch base URL (default `http://localhost:9200`)  |
| `arbiter.crypto.secret`                   | **Required.** Base64-encoded 32 random bytes used as the AES-256 key for encrypting Philter API keys and data-source credentials at rest. Generate with `openssl rand -base64 32`. Arbiter refuses to start if this is unset, not valid base64, or doesn't decode to exactly 32 bytes. |
| `arbiter.ingest-queue.poll-millis`        | Worker poll interval (default `5000`)                  |
| `arbiter.demo-data.enabled`               | Load sample files at startup if collections are empty  |
| `arbiter.demo-data.directory`             | Directory of files to seed (default: `sample-files`)   |

External Philter instances are added under **Admin → Philter** at runtime;
each instance can carry an optional API key (encrypted at rest with the
`arbiter.crypto.secret` above and sent as `Authorization: Bearer …` on every
outbound call).

## First run

Arbiter ships with a one-time bootstrap:

1. On any start where **no admin account exists** in the database, an
   administrator is created with email **`admin@philterd.ai`**. The
   password it gets depends on whether you have configured one:

    - If the **`ARBITER_ADMIN_INITIAL_PASSWORD`** environment variable is
      set to a value of at least 12 characters, that value is used. (The
      shipped `docker-compose.yaml` sets one — change it before running
      Arbiter for real.) The start-up banner confirms a configured
      password was used but does **not** echo it back, since it already
      lives in your compose file or secret store.
    - Otherwise, Arbiter generates a fresh random password and prints it
      to **standard output** during startup, inside a banner that looks
      like:

    ```
    ============================================================
      Arbiter bootstrap admin account created
    ============================================================
      Email:    admin@philterd.ai
      Password: <24-character random password>

      This password is shown ONCE. Sign in and change it from
      Settings -> Account immediately. It is not recoverable.
    ============================================================
    ```

    A generated password is **only printed once** — capture it from the
    start-up logs (`docker compose logs arbiter-webapp`, your service
    supervisor's stdout capture, etc.) before they roll over. If you lose
    it before signing in, delete the admin account from the database and
    restart to seed a new one.

    A **generated** password is also flagged for forced rotation: on first
    sign-in the admin is redirected to **Settings → Change password** and
    cannot reach any other page until they choose a new one — the value
    that was briefly visible in stdout shouldn't outlive the first
    session. A password that came from **`ARBITER_ADMIN_INITIAL_PASSWORD`**
    is **not** flagged for forced rotation; the operator already controls
    where that value lives (compose file, secret store), so Arbiter
    accepts it as the durable credential. Rotate it later from Settings
    when you wish.
2. On the first start with **empty `batches` / `documents` / `spans` collections
   and demo data enabled**, Arbiter loads any files under the configured demo
   directory into a sample batch and runs them through the redactor so the
   queue isn't empty.

Sign in at `/login` using the printed credentials, then **change the password
immediately** under [Personal settings](user-guide/settings.md).

## What you'll see after sign-in

| Area               | URL                       | Who can reach it                |
| ------------------ | ------------------------- | ------------------------------- |
| Dashboard          | `/`                       | All authenticated users         |
| Inbox              | `/inbox`                  | All authenticated users         |
| Batches            | `/batches`                | All authenticated users         |
| Document Queue       | `/queue`                  | All authenticated users         |
| Search             | `/search`                 | All authenticated users         |
| Add Documents      | `/upload`                 | All authenticated users         |
| Background Jobs    | `/jobs`                   | All authenticated users         |
| Personal settings  | `/settings`               | All authenticated users         |
| Documentation      | `/docs/`                  | Public (no login required)      |
| Ingest Queue       | `/admin/ingest-queue`     | Admin role only                 |
| Approval Rules     | `/admin/rules`            | Admin role only                 |
| Data Sources       | `/admin/data-sources`     | Admin role only                 |
| Reports            | `/reporting`              | Admin role only                 |
| Policies           | `/policies`               | Admin role only                 |
| Admin settings     | `/admin/users` …          | Admin role only                 |

Group membership scopes what reviewers see. By default reviewers only see
batches and documents in groups they belong to; admins can toggle a "Limit to
my groups" checkbox to switch between scoped and global views.

## Next steps

- New reviewer? Read [Concepts](concepts.md) and the User Guide.
- New admin? Read [Concepts](concepts.md) then jump to
  [Users and Groups](admin/users-and-groups.md).
- Integrating from another system? See [REST API](reference/api.md).
