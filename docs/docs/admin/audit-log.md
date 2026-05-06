# Audit log

Every state-changing action in Arbiter is recorded in the **`audit_log`**
collection in MongoDB. There are two ways to view and export it:

- **Admin → Audit log** (`/admin/audit`) — filtered slices across the whole
  system, exported as JSON or CSV. Admins only.
- **Audit Log popup** on the Document Queue — the full history for a single
  document (document-level events plus all of its span events), shown inline
  in a modal. Reviewers with access to the document can read the popup; only
  admins can download the CSV from it.

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
| `DOCUMENT_AUDIT_EXPORT`             | Document    | Admin downloaded the per-document audit log as CSV  |
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
- **Preview** — runs the same query as the export and shows the first 10
  matching entries inline so you can sanity-check filters before committing
  to a download. If the preview is empty, the download will be empty too —
  widen the time range or relax filters.
- **Download JSON** / **Download CSV** — same filters, two formats.

Both formats include every column above. CSV embeds the per-action `details`
map as a JSON-encoded string in a single quoted column so spreadsheet readers
preserve it.

The export is capped at 100,000 rows per request. Narrow the time range or
filters if you hit the cap.

## Per-document audit log

Every row in the **Document Queue** (`/queue`) has an **Audit Log** button
that opens a modal showing the full history for that one document. This
includes both document-level events (ingest, status changes, finalization)
and all events on spans that belong to the document (status changes, type
changes, manual creation, deletion, second-opinion requests). Entries are
sorted **newest first** and paginated 10 per page.

Any reviewer with access to the document's batch can open the popup. The
modal renders the PII text inline so reviewers can see, for example, that a
span's status was changed from `PENDING` to `APPROVED` for the value
`555-12-3456`.

### Download CSV

The popup has a **Download** button that exports the document's full audit
history (not just the page currently shown) as a CSV file, sorted newest to
oldest. The file is named `audit-log-<documentId>.csv`.

**Only administrators can download the audit log.** For non-admin users the
Download button is rendered disabled with a tooltip explaining the
restriction; the underlying API endpoint
(`GET /api/v1/documents/{id}/history.csv`) also rejects non-admin requests
with HTTP 403.

The export itself is audited. Before generating the CSV, Arbiter writes a
`DOCUMENT_AUDIT_EXPORT` entry capturing the admin who initiated the
download and the timestamp. Because the entry is recorded *before* the
audit log is queried, and the CSV is sorted newest-first, the export event
appears as the top row of every downloaded file — so each export is
self-attesting.

The CSV is intended as a chain-of-custody artifact and is **redacted on
export**: the PII text of each span is omitted. Instead, span entries
include the span's **location** so you can correlate the entry back to the
original document without leaking the PII value itself.

Columns:

| Column                | Notes                                                        |
| --------------------- | ------------------------------------------------------------ |
| `timestamp`           | ISO-8601 UTC instant                                         |
| `actor`               | Email of the user who performed the action (blank if system) |
| `action`              | The action code (e.g. `SPAN_UPDATE`, `DOCUMENT_INGEST`)      |
| `resourceType`        | `Document` or `Span`                                         |
| `resourceId`          | The document or span ID                                      |
| `spanType`            | PII type — populated only for span entries                   |
| `spanCharacterStart`  | Inclusive start offset in the document text (span entries)   |
| `spanCharacterEnd`    | Exclusive end offset in the document text (span entries)     |
| `spanPage`            | 1-based page number for PDF documents (span entries)         |
| `details`             | The per-action `details` map, JSON-encoded in a quoted field |

`spanText` is **never** present in the CSV. If you need to correlate an
entry back to the actual PII value, do so against your secured copy of the
original document using the character offsets and page number.

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
