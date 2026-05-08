# Getting started

## Prerequisites

- Java 21+
- A running MongoDB instance Arbiter can reach
- A running OpenSearch instance Arbiter can reach (for full-text search)
- A running Philter / Phileas redaction service Arbiter can call (Embedded
  Phileas works out of the box for development)

## Configuration

Arbiter is a Spring Boot application. The standard application properties apply.
The settings most users need to set are:

| Property                                  | Purpose                                                |
| ----------------------------------------- | ------------------------------------------------------ |
| `spring.data.mongodb.uri`                 | Connection URI for MongoDB                             |
| `spring.data.mongodb.database`            | Database name (default: `arbiter`)                     |
| `arbiter.opensearch.endpoint`             | OpenSearch base URL (default `http://localhost:9200`)  |
| `arbiter.crypto.secret`                   | 32-byte base64 (or any passphrase) used as the AES key for encrypting Philter API keys at rest. **Set this in any non-development deployment.** |
| `arbiter.ingest-queue.poll-millis`        | Worker poll interval (default `5000`)                  |
| `arbiter.demo-data.enabled`               | Load sample files at startup if collections are empty  |
| `arbiter.demo-data.directory`             | Directory of files to seed (default: `sample-files`)   |

External Philter instances are added under **Admin → Philter** at runtime;
each instance can carry an optional API key (encrypted at rest with the
`arbiter.crypto.secret` above and sent as `Authorization: Bearer …` on every
outbound call).

## First run

Arbiter ships with a one-time bootstrap:

1. On the first start with an **empty `users` collection**, an administrator is
   seeded with email **`admin@philterd.ai`** and password **`admin`**.
2. On the first start with **empty `batches` / `documents` / `spans` collections
   and demo data enabled**, Arbiter loads any files under the configured demo
   directory into a sample batch and runs them through the redactor so the
   queue isn't empty.

Sign in at `/login` using the seeded credentials, then **change the password
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
