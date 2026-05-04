# Getting started

## Prerequisites

- Java 17+
- A running MongoDB instance Arbiter can reach
- A running Philter / Phileas redaction service Arbiter can call (for the
  redaction backend)

## Configuration

Arbiter is a Spring Boot application. The standard application properties apply.
The settings most users need to set are:

| Property                          | Purpose                                                |
| --------------------------------- | ------------------------------------------------------ |
| `spring.data.mongodb.uri`         | Connection URI for MongoDB                             |
| `spring.data.mongodb.database`    | Database name (default: `arbiter`)                     |
| `arbiter.demo-data.enabled`       | Load sample files at startup if collections are empty  |
| `arbiter.demo-data.directory`     | Directory of files to seed (default: `sample-files`)   |

The redaction service URL is configured per the Philter client (see
`arbiter-philter-client`).

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

| Area              | URL                  | Who can reach it                |
| ----------------- | -------------------- | ------------------------------- |
| Queue (dashboard) | `/`                  | All authenticated users         |
| Batches           | `/batches`           | All authenticated users         |
| Upload            | `/upload`            | All authenticated users         |
| Personal settings | `/settings`          | All authenticated users         |
| Admin settings    | `/admin/users` …     | Admin role only                 |

Group membership scopes what reviewers see. By default reviewers only see
batches and documents in groups they belong to; admins can toggle a "Limit to
my groups" checkbox to switch between scoped and global views.

## Next steps

- New reviewer? Read [Concepts](concepts.md) and the User Guide.
- New admin? Read [Concepts](concepts.md) then jump to
  [Users and Groups](admin/users-and-groups.md).
- Integrating from another system? See [REST API](reference/api.md).
