# Notifications

Notification settings live under **Admin → Notifications**
(`/admin/notifications`). Today the page configures a single SMTP server for
outbound email.

## SMTP settings

| Field                    | Notes                                                                           |
| ------------------------ | ------------------------------------------------------------------------------- |
| Enable outbound email    | Master toggle                                                                   |
| Host                     | SMTP server hostname (e.g., `smtp.example.com`)                                 |
| Port                     | TCP port; typical values: `587` (STARTTLS), `465` (implicit SSL), `25` (plain)  |
| Username                 | SMTP auth username                                                              |
| Password                 | SMTP auth password                                                              |
| From address             | The `From:` address on outgoing mail                                            |
| From name                | Optional display name (e.g., "Arbiter")                                         |
| Use STARTTLS             | Negotiate TLS after `EHLO` on a plain connection (typical for port 587)         |
| Use implicit SSL/TLS     | Connect over TLS from the start (typical for port 465)                          |

You may not enable **both** STARTTLS and implicit SSL — pick one. Port is
validated to `[1, 65535]`.

## Password handling

The password field is not pre-filled. To preserve the saved password, leave
the field blank. To replace it, type a new value and save. To wipe it
entirely, tick **Clear stored password** and save.

The stored password is held as-is in MongoDB so Arbiter can present it to the
SMTP server when sending. This is a different threat model from user
passwords (which are one-way BCrypt-hashed). If you
need encryption-at-rest for SMTP credentials, deploy MongoDB with WiredTiger
encryption or pull the credential from a secret manager and disable storing
it here.

## Persistence

Settings are saved in the **`settings`** collection in MongoDB as a single
document with `_id = "notifications"`. The document layout is:

```json
{
  "_id": "notifications",
  "enabled": true,
  "host": "smtp.example.com",
  "port": 587,
  "username": "alerts@example.com",
  "password": "<as entered>",
  "fromAddress": "no-reply@example.com",
  "fromName": "Arbiter",
  "useStartTls": true,
  "useSsl": false
}
```

A `NOTIFICATION_SETTINGS_CHANGE` audit entry is written on every save with
non-secret fields and a `passwordChanged` boolean — the password value
itself is never written to the audit log.

## Inbox notifications (system-driven)

Separate from email, Arbiter also drops messages into a per-user **Inbox**
that surfaces with an unread-count badge in the sidebar. Today the
inbox-driver list is small but worth knowing about:

- **Data-import job completion** — when an OpenSearch or Elasticsearch
  import job ends (`COMPLETED` or `FAILED`), the user who started it gets
  a one-line summary: source name, batch, Successful / Failed / Skipped
  counts, plus any error message. See
  [Background Jobs](../user-guide/background-jobs.md#status-flow).

These messages are written to the `inbox_messages` collection and never
go out by email — even when SMTP is enabled — because they're meant for
the user's in-app inbox.
