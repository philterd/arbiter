# Getting started

## Prerequisites

- A running MongoDB instance Arbiter can reach, or nothing extra with the
  SQLite store (see [Database](reference/database.md))
- A running Valkey instance for sessions
- A running OpenSearch instance Arbiter can reach (for full-text search)
- A running Philter 4.x that Arbiter can call (`docker-compose.yaml`
  starts one for development)

## Configuration

Arbiter reads its settings from `application.properties` (or matching
environment variables); see [Configuration](reference/configuration.md) for the
full reference. The settings most operators need to set are:

| Property                                  | Purpose                                                |
| ----------------------------------------- | ------------------------------------------------------ |
| `arbiter.store`                           | `mongodb` (default) or `sqlite`. See [Database](reference/database.md). |
| `arbiter.sqlite.path`                     | The SQLite database file, with `arbiter.store=sqlite` (default `./data/arbiter.db`) |
| `spring.data.mongodb.uri`                 | Connection URI for MongoDB                             |
| `spring.data.mongodb.database`            | Database name (default: `arbiter`)                     |
| `arbiter.philter.url`                     | **Required.** The URL of Arbiter's Philter (`ARBITER_PHILTER_URL`), such as `http://philter:8080` |
| `arbiter.philter.api-key`                 | **Required.** The Philter API key for background work such as ingest (`ARBITER_PHILTER_API_KEY`), for a Philter user who is not an administrator. Set it through the environment. |
| `arbiter.crypto.secret`                   | **Required.** Base64-encoded 32 random bytes used as the AES-256 key for encrypting data-source credentials and other stored secrets at rest. Generate with `openssl rand -base64 32`. Arbiter refuses to start if this is unset, not valid base64, or doesn't decode to exactly 32 bytes. |
| `arbiter.ingest-queue.poll-millis`        | Worker poll interval (default `5000`)                  |
| `arbiter.demo-data.enabled`               | Load sample files at startup if collections are empty  |
| `arbiter.demo-data.directory`             | Directory of files to seed (default: `sample-files`)   |

Arbiter needs a running Philter 4.x; it does not detect PII itself.
See [Configure Philter](workflow.md#1-configure-philter).

The OpenSearch cluster used to power the full-text search index is
configured at runtime under **Admin → Settings → Full text search** —
the section accepts the endpoint, optional basic-auth username and
password, and the index name. There is no longer an environment
variable for the OpenSearch endpoint; an admin must save the form once
on first startup so the values are written to the database.

## First run

Arbiter creates no accounts: people sign in with their Philter username and password, and the
first administrator is Philter's `admin`. Give it a password with `PHILTER_BOOTSTRAP_API_KEY`, then
sign in at `/login` as `admin`; see [First sign-in](security.md#first-sign-in). The shipped
`docker-compose.yaml` runs a Philter with development-only keys.

On the first start with **empty `batches` / `documents` / `spans` collections and demo data
enabled**, Arbiter loads any files under the configured demo directory into a sample batch and runs
them through the redactor so the queue isn't empty.

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
