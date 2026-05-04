# Audit log

Every state-changing action in Arbiter is recorded in the **`audit_log`**
collection in MongoDB. Admins can export filtered slices under
**Admin → Audit log** (`/admin/audit`).

## What gets logged

| Action                              | Resource    | Notes                                              |
| ----------------------------------- | ----------- | -------------------------------------------------- |
| `LOGIN` (success / failure)         | User        | Authentication outcome                              |
| `LOGOUT`                            | User        | Manual sign-out                                     |
| `BATCH_CREATE`                      | Batch       | Includes name, group, thresholds                    |
| `BATCH_GROUP_CHANGE`                | Batch       | Old → new group                                     |
| `BATCH_THRESHOLDS_CHANGE`           | Batch       | Both PII and Document threshold deltas              |
| `BATCH_WEIGHTS_CHANGE`              | Batch       | Override map                                        |
| `BATCH_WEIGHTS_RESET`               | Batch       | All overrides cleared                               |
| `BATCH_CLOSE`                       | Batch       | Records who closed it and when                      |
| `DOCUMENT_UPLOAD`                   | Document    | Web upload                                          |
| `DOCUMENT_INGEST`                   | Document    | API ingest                                          |
| `DOCUMENT_STATUS_CHANGE`            | Document    | Approve / Reject / Unapprove                        |
| `SPAN_UPDATE`                       | Span        | Status and/or type change                           |
| `SPAN_REDACT_LIKE`                  | Span        | Counts of new-and-approved spans                    |
| `USER_CREATE` / `_UPDATE` / `_DELETE` | User      | Admin user management                               |
| `GROUP_CREATE` / `_UPDATE` / `_DELETE` | Group    | Admin group management                              |
| `API_KEY_GENERATE` / `_REVOKE`      | User        | Per-user API key lifecycle                          |
| `PASSWORD_CHANGE`                   | User        | Self-service password change                        |
| `NOTIFICATION_SETTINGS_CHANGE`      | Settings    | SMTP settings save (excluding the password value)   |

Each entry stores:

- `timestamp` (UTC `Instant`)
- `userEmail` and `userId` (when the actor is signed in)
- `action`, `resourceType`, `resourceId`
- `outcome` (`SUCCESS` / `FAILURE`)
- `ipAddress` (honors `X-Forwarded-For` first hop)
- `details` (per-action contextual map; never includes secrets)

## Export

The form has:

- **Start time** / **End time** — required, interpreted in the **server's**
  local timezone. The page pre-fills the past 24 hours.
- **User email** — optional exact match.
- **Resource type** — optional, one of User / Group / Batch / Document /
  Span (or "All").
- **Resource ID** — optional exact match.
- **Download JSON** / **Download CSV** — same filters, two formats.

Both formats include every column above. CSV embeds the per-action `details`
map as a JSON-encoded string in a single quoted column so spreadsheet readers
preserve it.

The export is capped at 100,000 rows per request. Narrow the time range or
filters if you hit the cap.

## Storage and retention

Entries are written via Spring Data MongoDB. Indexes are declared on
`timestamp`, `userEmail`, `action`, `resourceType`, and `resourceId`.
Arbiter does not automatically expire entries — set up a TTL index on
`timestamp` or a periodic deletion job if you need bounded retention.

## Failure modes

If writing an audit entry fails (e.g., MongoDB is unavailable for a moment),
the action it describes still succeeds — the audit service swallows the
exception and logs a warning. This trades audit completeness for application
availability; review logs from the Arbiter process for any
"Failed to write audit log entry" warnings.
