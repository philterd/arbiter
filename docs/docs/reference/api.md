# REST API

Arbiter exposes a JSON-over-HTTP API under `/api/v1`. The API surface is split
into two categories that authenticate differently.

## Authentication

### Programmatic (Bearer-only)

These endpoints are intended for scripts, integrations, and downstream
consumers. They accept **only** a personal API key sent as a Bearer token —
session cookies are explicitly stripped by the security filter chain so a
logged-in admin's browser session cannot be CSRF'd into reaching them. The
Bearer-only set is small and stable:

| Method | Path |
|---|---|
| `POST` | `/api/v1/ingest` |
| `GET`  | `/api/v1/search` |
| `POST` | `/api/v1/documents/{id}/finalize` |
| `GET`  | `/api/v1/documents/{id}/audit` |

To use them, generate an API key from
[Personal settings](../user-guide/settings.md) and send it on every request:

```http
Authorization: Bearer <your-api-key>
```

Arbiter stores only the SHA-512 hash of the key. The plaintext value is shown
once at generation and cannot be recovered. Rotate by generating a new key
(which replaces the old one) or revoke the existing one. Failed authentication
(no header, malformed header, or unknown key) returns `401 Unauthorized`.

### Session-allowed (browser-UI shared)

The remaining `/api/v1/**` endpoints are used by Arbiter's own web UI and
accept either a Bearer token or the browser's session cookie. Cross-origin
abuse is blocked by the browser's same-origin policy plus the absence of a
permissive CORS configuration; Bearer authentication still works for
programmatic clients that prefer to use them. Endpoints in this category are
called out below per-section. The API key carries the same role and group
permissions as the user that owns it; session callers carry whatever role and
groups their account has.

## Document ingestion

### `POST /api/v1/ingest`

Bearer-only. Submit a plain-text document. Ingestion is **asynchronous**: the document is
persisted in `PENDING` and placed on the redaction queue. A background worker
runs Philter in arrival order; once redaction completes, the document moves to
`REVIEW_REQUIRED` (PII detected with low-confidence spans), `AUDIT_REQUIRED`
(eligible for auto-approval but sampled for review per the batch's audit
sampling rate), or `AUTO_APPROVED`.

Request body:

```json
{
  "batchId":  "string",
  "name":     "string",
  "text":     "string",
  "priority": 2
}
```

`priority` is optional. It accepts an integer in `1..3` (`1` Low, `2` Normal,
`3` High); omitting it or sending `null` defaults to Normal. The value is
stored on the document and surfaced as a chevron icon on the
[Document Queue](../user-guide/queue.md#priority-column). It does not affect
ingest ordering — redaction still runs oldest-first.

| Status | Meaning                                                                |
| ------ | ---------------------------------------------------------------------- |
| `202`  | Accepted; body `{"taskId": "..."}`. Redaction runs asynchronously.     |
| `400`  | `batchId` does not exist (or required fields missing/invalid, including `priority` outside `1..3`). |
| `403`  | Caller does not have access to that batch.                             |
| `409`  | Batch is closed; body includes `"closed": true`.                       |

The returned `taskId` is the document's id. Poll
[`GET /api/v1/documents/{id}/spans`](#get-apiv1documentsidspans) or
[`GET /api/v1/queue`](#get-apiv1queue) to track its progress out of `PENDING`.

A SHA-512 hash of the submitted `text` (UTF-8 bytes) is recorded on the
document at ingest time — see
[Security · Document content integrity](../security.md#document-content-integrity).

## Triage

### `GET /api/v1/queue`

Session-allowed. List documents the caller can see, paged by sort field.

| Query param      | Default       | Meaning                                                |
| ---------------- | ------------- | ------------------------------------------------------ |
| `page`           | `0`           | Zero-indexed page (negative values are clamped to `0`)  |
| `size`           | `10`          | Page size, clamped to the range `[1, 100]`              |
| `batchId`        | —             | Filter to one batch                                    |
| `status`         | —             | Filter to one status                                   |
| `filename`       | —             | Substring match on filename, case-insensitive          |
| `myGroupsOnly`   | `false`       | Admin opt-in: restrict admins to their own groups      |
| `sort`           | `riskScore`   | One of `riskScore`, `status`, `batchId`, `filename`, `priority` |
| `dir`            | `desc`        | `asc` or `desc`                                        |

`size` is hard-capped at 100 — values above that are silently lowered, and
values below `1` are raised to `1`. Page through larger result sets with
successive `page` values rather than a larger `size`.

Non-admins are always restricted to their groups; the `myGroupsOnly` parameter
only affects admin callers.

Response is a Spring `Page<Map>` shape:

```json
{
  "content": [
    {
      "id": "string",
      "filename": "string",
      "status": "PENDING|REVIEW_REQUIRED|AUDIT_REQUIRED|AUTO_APPROVED|APPROVED|REJECTED|FAILED",
      "riskScore": 0.0,
      "batchId": "string",
      "batchName": "string",
      "autoApproved": false,
      "documentThreshold": 0.25,
      "priority": 2
    }
  ],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 10
}
```

`autoApproved` is the **derived** display flag: it's true when the document's
risk score is at or below `documentThreshold` and the document is neither in a
user-decided terminal state (`APPROVED`, `REJECTED`, `FAILED`) nor in
`AUDIT_REQUIRED`. The stored `status` field is independent.

### `GET /api/v1/batches`

Session-allowed. List batches the caller can target. Honors the same
`myGroupsOnly` query param. Returns a JSON array of `{id, name}`.

## Documents

### `GET /api/v1/documents/{id}/spans`

Session-allowed. Return every `Span` row in the document. Useful for building
a custom review client or for reconciling the redactor's output with
downstream systems.

`404` if the document doesn't exist or the caller lacks group access.

### `POST /api/v1/documents/{documentId}/spans`

Session-allowed. Manually create a span at an explicit character range. Used
by the review UI when a reviewer highlights uncovered PII; the API is also
available to clients.

```json
{ "type": "ssn", "start": 42, "end": 53 }
```

`type` is validated against the [PII types list](pii-types.md). The new span is
persisted with `confidence: 1.0`, `status: APPROVED`, and `manuallyCreated:
true`.

| Status | Meaning                                                              |
| ------ | -------------------------------------------------------------------- |
| `200`  | Returns the saved `Span` JSON.                                       |
| `400`  | Missing/invalid `type`, `start`, or `end`; range exceeds the text.   |
| `404`  | Document not found or caller lacks access.                           |
| `409`  | Document is in a terminal state and cannot be edited.                |

### `POST /api/v1/documents/{id}/finalize`

Bearer-only. Produce the redacted text for a document by sending its approved
spans to Philter and applying them. The response is the post-redaction string. On
success, the document is transitioned to `FINALIZED` and the rendered
redacted text is persisted on the document so a later download still works
even if a finalization policy clears the source text.

```json
{ "finalizedText": "string" }
```

| Status | Meaning                                                                                |
| ------ | -------------------------------------------------------------------------------------- |
| `200`  | Returns `{ "finalizedText": "..." }` and the document is now `FINALIZED`.               |
| `404`  | Document not found, or the caller lacks group access.                                   |
| `409`  | Document is not in `APPROVED`, **or** its source text is unavailable (e.g. cleared by a finalization policy on a prior pass) and cannot be re-finalized. |

### `GET /api/v1/documents/{id}/audit`

Bearer-only. Return a redaction audit trail — every span on the document
with its `text`, `type`, `confidence`, and current `status`. Useful for
after-the-fact review or compliance reporting.

```json
[
  { "text": "...", "type": "ssn", "confidence": 0.92, "status": "APPROVED" }
]
```

`404` if the document doesn't exist or the caller lacks group access.

### `GET /api/v1/documents/{id}/history`

Session-allowed. Return the full audit history for a document — document-level
events and all span events — as a JSON array sorted newest first. Powers the
Audit Log popup on the Document Queue.

**Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`.** Returns `403` for any other
caller because the history includes raw PII span text.

Each element:

```json
{
  "timestamp": "2026-05-01T12:00:00Z",
  "actor":     "<mongodb-user-id>",
  "action":    "SPAN_UPDATE",
  "resourceType": "Span",
  "resourceId":   "...",
  "details":   {}
}
```

The `actor` field defaults to the MongoDB user ID. Pass `?resolveActors=true`
to receive the user's email address instead — this parameter requires
`ROLE_ADMIN` or `ROLE_AUDITOR` and returns `403` otherwise.

### `GET /api/v1/documents/{id}/history.csv`

Session-allowed. Download the document's full audit history (document-level
events plus all events on its spans) as a CSV, sorted newest first. Powers
the **Download** button on the Document Queue's Audit Log popup. See
[Audit log](../admin/audit-log.md#download-csv) for the column list.

**Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`.** Returns `403` for any other
caller. The CSV deliberately omits PII text — span entries include
`spanCharacterStart`, `spanCharacterEnd`, and `spanPage` instead. The `actor`
column contains the actor's email address (the CSV is admin/auditor-only, so
email exposure is appropriate).

### `GET /api/v1/documents/{id}/certificate`

Session-allowed. Return the redaction certificate for a finalized document —
a JSON object summarising the document hash, finalize timestamp, and span
counts. Powers the Certificate popup on the Document Queue. The caller must
have group access to the document; `404` is returned if the document doesn't
exist or the caller lacks access.

### `GET /api/v1/documents/{id}/comments`

Session-allowed. Return reviewer comments left on the document, oldest first.

```json
[
  { "id": "...", "userEmail": "user@example.com",
    "timestamp": "2026-05-04T13:00:00Z", "text": "..." }
]
```

### `POST /api/v1/documents/{id}/comments`

Session-allowed. Add a comment to the document. The request body is a JSON
object with a single `text` string (max 4 000 characters; surrounding
whitespace is trimmed). Returns the saved comment in the same shape the GET
above produces.

```json
{ "text": "..." }
```

## Spans

### `PATCH /api/v1/spans/{id}`

Session-allowed. Update a span's status, type, or both.

```json
{
  "status":        "APPROVED|REJECTED|PENDING|NEEDS_SECOND_OPINION",
  "type":          "ssn",
  "reason":        "...",
  "exemptionCode": "..."
}
```

| Field           | Required | Notes                                                                                     |
| --------------- | -------- | ----------------------------------------------------------------------------------------- |
| `status`        | optional | One of the allowed statuses. Sending neither `status` nor `type` returns `400`.            |
| `type`          | optional | New PII type; validated against the [PII types list](pii-types.md).                        |
| `reason`        | optional | Required when **overturning** another reviewer's prior `APPROVED` decision (changing status away from `APPROVED` while the prior approval was recorded by a different actor). Returns `409 OVERTURN_REASON_REQUIRED` otherwise. Recorded in the audit trail. |
| `exemptionCode` | optional | Free-form string applied only when the new `status` is `APPROVED`. Cleared automatically when the span moves out of `APPROVED`. |

Returns the updated `Span` object.

`409` if the parent document is in a terminal state, or if an overturn is
attempted without a `reason`.

### `DELETE /api/v1/spans/{id}`

Session-allowed. Hard-delete a span. **Only manually-created spans can be
deleted** — for spans the redactor produced, flip status to `REJECTED`
instead.

```json
{ "id": "...", "deleted": true }
```

`400` if the span was redactor-created. `409` if the parent document is
terminal.

### `POST /api/v1/spans/{id}/redact-like`

Session-allowed. Find every other occurrence of the source span's text in the
parent document and approve each match with the source span's PII type. New
`Span` rows are created where matches don't already have one; existing spans
at exact ranges are flipped to `APPROVED` and aligned to the source type.
Overlapping non-exact matches are skipped to avoid duplicate spans.

Requires `Content-Type: application/json` (the request body itself is
ignored; the JSON content type is enforced as a CSRF defence so cross-site
form posts can't trigger this endpoint).

Response:

```json
{ "created": 0, "approved": 0 }
```

`created` is the number of new spans inserted. `approved` is the number of
existing spans flipped to approved.

`400` if the source span has empty text. `404` if the span or its document is
missing.

### `POST /api/v1/spans/{id}/reset`

Session-allowed. Revert a span back to its previous status. The intended use
is "I clicked Approve / Reject by mistake" — the endpoint moves a span out of
its terminal state and back into review. The optional JSON body
`{"originalStatus": "PENDING|REVIEW_REQUIRED"}` selects the target status; an
empty body falls back to the span's prior status as recorded in the audit log.

Requires `Content-Type: application/json`. Returns the updated `Span` JSON.

`404` if the span doesn't exist or the caller lacks group access. `409` if
the parent document is in a terminal state.

### `GET /api/v1/spans/{id}/history`

Session-allowed. Return the audit history for a single span as a JSON array
sorted newest first. Accessible to any authenticated user with group access
to the span's parent document.

```json
[
  {
    "timestamp":    "2026-05-01T12:00:00Z",
    "actor":        "<mongodb-user-id>",
    "action":       "SPAN_UPDATE",
    "resourceType": "Span",
    "resourceId":   "...",
    "details":      {}
  }
]
```

The `actor` field defaults to the MongoDB user ID of the actor to avoid
leaking email addresses to other reviewers. Pass `?resolveActors=true` to
receive email addresses instead — this parameter requires `ROLE_ADMIN` or
`ROLE_AUDITOR` and returns `403` otherwise.

`404` if the span doesn't exist or the caller lacks group access.

## Search

### `GET /api/v1/search`

Bearer-only. Full-text search across the OpenSearch index of ingested documents. Each
document is indexed at ingest time with its filename, batch, status, and full
original text.

| Query param | Default | Meaning                                          |
| ----------- | ------- | ------------------------------------------------ |
| `q`         | —       | Match query (required, runs against the text)   |
| `offset`    | `0`     | First hit to return                              |
| `size`      | `10`    | Max hits per page (capped at 100)                |

Response:

```json
{
  "query": "...",
  "offset": 0,
  "size": 10,
  "total": 42,
  "hits": [
    {
      "id": "...",
      "batchId": "...",
      "filename": "...",
      "status": "AUTO_APPROVED",
      "highlights": ["… <em>match</em> snippet …"]
    }
  ]
}
```

Results are pre-filtered to batches the caller can access. Non-admin callers
only see hits from their own group's batches; `total` reflects that filtered
count, not the full index size. Inaccessible hits are silently excluded — they
are never returned as placeholder rows.

If OpenSearch is unreachable, a query returns an empty result set rather than
failing the request — search is best-effort.

## LLM-as-a-Judge

These endpoints proxy a configured Ollama instance to provide an LLM second
opinion on the redactor's output. Configuration lives under
**Admin → LLM-as-a-Judge**.

!!! warning "PII leaves Arbiter on every call"
    Both `explain` and `second-opinion` send the full unredacted document text
    and all PII span values to Ollama. Each call is recorded in the audit log
    as `DOCUMENT_PII_SENT_TO_LLM` **before** the HTTP request is sent, so the
    entry exists even if Ollama is unreachable. Ollama must be deployed with
    request-body logging disabled (`OLLAMA_DEBUG=0`) to prevent PII from
    appearing in Ollama's logs.

### `GET /api/v1/ollama/{instanceId}/models`

Session-allowed. List the models installed on a configured Ollama instance.

```json
{ "instanceId": "...", "instanceName": "...", "models": ["llama3", "mistral"] }
```

`404` if the instance id is unknown. `502` if Ollama is unreachable.

### `POST /api/v1/documents/{documentId}/explain`

Session-allowed. Ask the LLM to explain the PII risk in a document.

```json
{ "instanceId": "...", "model": "llama3" }
```

Response:

```json
{ "instanceName": "...", "model": "llama3", "response": "..." }
```

### `POST /api/v1/spans/{spanId}/second-opinion`

Session-allowed. Ask the configured Second Opinion default Ollama
instance/model whether the named span is genuinely PII or a likely false
positive. The instance and model are chosen from the LLM-as-a-Judge defaults
— the request body is empty but the `Content-Type: application/json` header
is required (CSRF defence).

```json
{ "instanceName": "...", "model": "...",
  "sourceText": "...", "sourceType": "ssn",
  "response": "..." }
```

`400` if no Second Opinion default is configured.

## Policies

These endpoints power the Phileas redaction-policy editor under
**Admin → Policies**. The framework gates them to `ROLE_ADMIN` or
`ROLE_AUDITOR` for reads — non-admin callers get `403`.

### `GET /api/v1/policies`

Session-allowed. List the policies installed on a configured Philter
instance, or on the embedded Phileas runtime when no instance is specified.

| Query param  | Default     | Meaning                                              |
| ------------ | ----------- | ---------------------------------------------------- |
| `instanceId` | `embedded`  | Philter instance id, or `embedded` for the built-in. |

Response:

```json
{ "instanceId": "embedded", "policies": ["Default", "..."] }
```

`502` when the named Philter instance is unreachable; `404` if `instanceId`
doesn't match any configured instance.

### `GET /api/v1/policies/content`

Session-allowed. Fetch the JSON content of one named policy on the chosen
instance. Both query parameters are required.

| Query param  | Required | Meaning                                              |
| ------------ | -------- | ---------------------------------------------------- |
| `instanceId` | yes      | Philter instance id, or `embedded` for the built-in. |
| `name`       | yes      | Policy name; restricted to letters, digits, hyphens, and underscores (1–64 chars). |

Response:

```json
{ "name": "Default", "content": "{ ...JSON policy... }" }
```

`content` is the raw policy JSON as a string (the embedded runtime stores
policies as text; remote Philter responses are returned verbatim).

`400` for malformed names (the regex check rejects path-traversal attempts).
`404` when the named policy doesn't exist on the instance. `502` when the
remote Philter instance is unreachable.

## Browser-only internal endpoints

The following paths exist under `/api/v1/**` but are intended only for the
in-page JavaScript that drives the review UI. They are session-callable, do
nothing harmful in isolation, and are documented here purely so that traffic
from arbiter's own UI doesn't look surprising in HTTP logs:

| Path | Purpose |
|---|---|
| `POST /api/v1/review/{documentId}/pulse` | Sliding-expiry heartbeat sent every ~30 s by the review page so the document's pessimistic edit lock doesn't expire while the reviewer is still on the page. Operates on the caller's own lock only. |
| `POST /api/v1/review/{documentId}/release` | `navigator.sendBeacon`'d when the reviewer leaves the page. Releases the caller's own lock. |
| `GET /api/v1/review/{documentId}/similar` | Backs the "Find similar documents" button on the review page. Returns up to 10 hits filtered to the caller's accessible batches. |

There's no programmatic-API reason to call these — use `PATCH /spans/{id}` and
`GET /search` instead.

## Errors

Error responses have a JSON body with at least an `error` field describing the
issue. Status codes follow the table per endpoint above.
