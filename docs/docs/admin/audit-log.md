# Audit log

Every state-changing action in Arbiter is recorded in the **`audit_log`**
collection in MongoDB. There are two ways to view and export it:

- **Admin → Audit log** (`/admin/audit`) — filtered slices across the whole
  system, exported as JSON or CSV. Admins only.
- **Audit Log popup** on the Document Queue — the full history for a single
  document (document-level events plus all of its span events), shown inline
  in a modal. **Admin only** — the underlying
  `GET /api/v1/documents/{id}/history` endpoint requires `ROLE_ADMIN`.
  Admins can also download the history as CSV from the popup.

## What gets logged

| Action                              | Resource    | Notes                                              |
| ----------------------------------- | ----------- | -------------------------------------------------- |
| `LOGIN` (success / failure)         | User        | Authentication outcome. Every form-login attempt produces one row regardless of result. A failure row carries a `reason` detail naming the underlying exception (e.g. `BadCredentialsException`, `LockedException`, `UsernameNotFoundException`) so an operator can tell wrong-password from unknown-user from rate-limited at a glance. The attempted email is always recorded, even when the email doesn't match a known account. |
| `LOGOUT`                            | User        | Manual sign-out                                     |
| `BATCH_CREATE`                      | Batch       | Includes name, group, thresholds                    |
| `BATCH_GROUP_CHANGE`                | Batch       | Old → new group                                     |
| `BATCH_THRESHOLDS_CHANGE`           | Batch       | Both PII and Document threshold deltas              |
| `BATCH_WEIGHTS_CHANGE`              | Batch       | Override map                                        |
| `BATCH_WEIGHTS_RESET`               | Batch       | All overrides cleared                               |
| `BATCH_CLOSE`                       | Batch       | Records who closed it and when                      |
| `DOCUMENT_UPLOAD`                   | Document    | Web upload                                          |
| `DOCUMENT_INGEST`                   | Document    | API ingest (`POST /api/v1/ingest`)                  |
| `DOCUMENT_IMPORT`                   | Document    | One row per document pulled in by a data-import background job. Outcome is `SUCCESS` for newly-imported documents and `SKIPPED` for placeholders written when the source row already had a Document. Details include the import `jobId` and the source attribution (`sourceSystem`, `sourceUrl`, `sourceIndex`, `sourceDocId`) so the document can be traced back to the OpenSearch / Elasticsearch hit, S3 object, or local file it came from. |
| `DATA_IMPORT_STARTED`               | BackgroundJob | Fired when a data-import job is promoted from PENDING to RUNNING by the dispatcher. Details include the job's `type`, `sourceId`, `batchId`, and friendly names. |
| `DATA_IMPORT_COMPLETED`             | BackgroundJob | Terminal SUCCESS row for a data-import job. Details include `processed`, `failed`, `skipped` counters. |
| `DATA_IMPORT_FAILED`                | BackgroundJob | Terminal FAILURE row for a data-import job (validation failure at queue time, or runtime error during the run). Carries the same counters plus an `error` string. |
| `DOCUMENT_STATUS_CHANGE`            | Document    | Generic status transition; also fires on Approve / Unapprove / Unreject |
| `DOCUMENT_APPROVAL`                 | Document    | Reviewer approved a document; payload includes `approvedBy`, `acquired`, and `required` approval counts |
| `DOCUMENT_REJECT`                   | Document    | Reviewer rejected a document; payload includes `previous` status and `rejectedBy` |
| `DOCUMENT_UNAPPROVE`                | Document    | Approval rescinded and document returned to review  |
| `DOCUMENT_UNREJECT`                 | Document    | Rejection rescinded and document returned to review |
| `DOCUMENT_FINALIZE`                 | Document    | Document finalized; includes `certificateId` and `documentHash` |
| `DOCUMENT_DOWNLOAD`                 | Document    | Reviewer downloaded a finalized redacted document   |
| `DOCUMENT_AUDIT_EXPORT`             | Document    | Admin downloaded the per-document audit log as CSV  |
| `DOCUMENT_PII_SENT_TO_LLM`          | Document    | Fired **before** each outbound Ollama LLM call (Explain or Second Opinion). Records the Ollama instance, model, and span count. Written before the HTTP request so the entry exists even if Ollama is unreachable or returns an error. See [LLM-as-a-Judge](#llm-as-a-judge-events). |
| `DOCUMENT_LOCK_BROKEN`              | Document    | Admin forcibly cleared a review lock held by another user |
| `FINALIZATION_POLICY_APPLIED`       | Document    | A finalization policy ran after document finalize; payload includes `policyId`, `policyName`, `option`, and `action` |
| `SPAN_UPDATE`                       | Span        | Status and/or type change                           |
| `SPAN_REDACT_LIKE`                  | Span        | Counts of new-and-approved spans                    |
| `USER_CREATE` / `_UPDATE` / `_DELETE` | User      | Admin user management                               |
| `GROUP_CREATE` / `_UPDATE` / `_DELETE` | Group    | Admin group management                              |
| `API_KEY_GENERATE` / `_REVOKE`      | User        | Per-user API key lifecycle                          |
| `PASSWORD_CHANGE`                   | User        | Self-service password change                        |
| `NOTIFICATION_SETTINGS_CHANGE`      | Settings    | SMTP settings save (excluding the password value)   |
| `INBOX_MESSAGE_READ`                | InboxMessage | User marked an inbox notification as read           |
| `INBOX_MESSAGE_ARCHIVED`            | InboxMessage | User archived an inbox notification                 |
| `OPENSEARCH_DATASOURCE_CREATE` / `_UPDATE` / `_DELETE` | OpenSearchDataSource | Data source registered, edited (with `passwordChanged` boolean), or removed (see [Data sources](data-sources.md)) |
| `ELASTICSEARCH_DATASOURCE_CREATE` / `_UPDATE` / `_DELETE` | ElasticsearchDataSource | Same shape as OpenSearch — separate collection, separate audit lineage |
| `S3_DATASOURCE_CREATE` / `_DELETE`  | S3DataSource | S3 data source registered or removed                |
| `RDB_DATASOURCE_CREATE` / `_DELETE` | RelationalDbDataSource | Relational database data source registered or removed |
| `RDB_DANGEROUS_SQL_BLOCKED`         | RelationalDbDataSource | An RDB save was rejected because the SQL contains a disallowed keyword (`DELETE`, `TRUNCATE`, `DROP`). Payload includes the matched keywords and offending SQL; entityId is `null` because nothing was saved. |
| `LOCAL_DATASOURCE_CREATE` / `_DELETE` | LocalDirectoryDataSource | Local directory data source registered or removed |

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

**Admin only.** The underlying `GET /api/v1/documents/{id}/history` endpoint
requires `ROLE_ADMIN` because the history includes raw PII span text. Non-admin
reviewers receive `403` if they call the endpoint directly; the popup button
is hidden for non-admins in the UI.

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
| `actor`               | Email address of the user who performed the action (blank if system). The CSV always uses the email address; the JSON history endpoints (`GET …/history` and `GET …/spans/{id}/history`) use the MongoDB user ID by default — pass `?resolveActors=true` (admin only) to receive email addresses there instead. |
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

## LLM-as-a-Judge events

Every call that sends document content to an Ollama instance produces a
`DOCUMENT_PII_SENT_TO_LLM` entry. The event is written **before** the HTTP
request leaves Arbiter so the record exists even when Ollama is unreachable
or returns an error.

| Field in `details`  | Content                                                         |
| ------------------- | --------------------------------------------------------------- |
| `instanceId`        | MongoDB ID of the Ollama instance                               |
| `instanceName`      | Display name of the Ollama instance                             |
| `model`             | Model name sent to Ollama (e.g. `llama3`)                       |
| `spanCount`         | Number of PII spans included in the prompt (Explain calls only) |
| `spanId`            | ID of the span being evaluated (Second Opinion calls only)      |
| `spanType`          | PII type of that span (Second Opinion calls only)               |

The resource type is always `Document` and the resource ID is the parent
document's ID — even for Second Opinion calls, which originate from a span —
because the full document text is always included in the prompt.

!!! warning "Ollama logging"
    Arbiter sends the full unredacted document text and all PII span values
    to Ollama in the request body. **Ollama must be deployed with request-body
    logging disabled.** If `OLLAMA_DEBUG=1` is set or your Ollama deployment
    forwards request bodies to an external logging sink, PII will appear in
    logs outside Arbiter's security boundary. See
    [Admin → LLM-as-a-Judge](../admin/security.md) for configuration
    requirements.

## Storage and retention

Audit entries are stored in MongoDB and are indexed by timestamp, the actor's
email, action, resource type, and resource ID so the Audit Log search remains
fast as the collection grows. Arbiter does not automatically expire entries —
set up a database-level TTL on the timestamp field or a periodic deletion job
if you need bounded retention.

## Failure modes

If writing an audit entry fails (e.g., MongoDB is unavailable for a moment),
the action it describes still succeeds — the audit service swallows the
exception and logs a warning. This trades audit completeness for application
availability; review logs from the Arbiter process for any
"Failed to write audit log entry" warnings.
