# REST API

Arbiter exposes a JSON-over-HTTP API under `/api/v1`. All endpoints require
authentication via a personal **API key** sent as a Bearer token. The API key
carries the same role and group permissions as the user that owns it.

## Authentication

Generate an API key from [Personal settings](../user-guide/settings.md). Send it
on every request:

```http
Authorization: Bearer <your-api-key>
```

Arbiter stores only the SHA-512 hash of the key. The plaintext value is shown
once at generation and cannot be recovered. Rotate by generating a new key
(which replaces the old one) or revoking the existing one.

Failed authentication (no header, malformed header, or unknown key) returns
`401 Unauthorized`.

## Document ingestion

### `POST /api/v1/ingest`

Submit a plain-text document. Ingestion is **asynchronous**: the document is
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
[Document Queue](../user-guide/queue.md#priority-icon). It does not affect
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

List documents the caller can see, paged by sort field.

| Query param      | Default       | Meaning                                                |
| ---------------- | ------------- | ------------------------------------------------------ |
| `page`           | `0`           | Zero-indexed page                                      |
| `size`           | `10`          | Page size                                              |
| `batchId`        | —             | Filter to one batch                                    |
| `status`         | —             | Filter to one status                                   |
| `filename`       | —             | Substring match on filename, case-insensitive          |
| `myGroupsOnly`   | `false`       | Admin opt-in: restrict admins to their own groups      |
| `sort`           | `riskScore`   | One of `riskScore`, `status`, `batchId`, `filename`, `priority` |
| `dir`            | `desc`        | `asc` or `desc`                                        |

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

List batches the caller can target. Honors the same `myGroupsOnly` query
param. Returns a JSON array of `{id, name}`.

## Documents

### `GET /api/v1/documents/{id}/spans`

Return every `Span` row in the document. Useful for building a custom review
client or for reconciling the redactor's output with downstream systems.

`404` if the document doesn't exist or the caller lacks group access.

### `POST /api/v1/documents/{documentId}/spans`

Manually create a span at an explicit character range. Used by the review UI
when a reviewer highlights uncovered PII; the API is also available to clients.

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

Produce the redacted text for a document by sending its approved spans to
Philter and applying them. The response is the post-redaction string and the
document is transitioned to `AUTO_APPROVED`.

```json
{ "finalizedText": "string" }
```

`404` if the document doesn't exist or the caller lacks group access.

### `GET /api/v1/documents/{id}/audit`

Return a redaction audit trail — every span on the document with its
`text`, `type`, `confidence`, and current `status`. Useful for after-the-fact
review or compliance reporting.

```json
[
  { "text": "...", "type": "ssn", "confidence": 0.92, "status": "APPROVED" }
]
```

`404` if the document doesn't exist or the caller lacks group access.

### `GET /api/v1/documents/{id}/history.csv`

Download the document's full audit history (document-level events plus all
events on its spans) as a CSV, sorted newest first. Powers the **Download**
button on the Document Queue's Audit Log popup. See
[Audit log](../admin/audit-log.md#download-csv) for the column list.

**Admin only.** Returns `403` for non-admin callers. The CSV deliberately
omits PII text — span entries include `spanCharacterStart`,
`spanCharacterEnd`, and `spanPage` instead.

### `GET /api/v1/documents/{id}/comments`

Return reviewer comments left on the document, oldest first.

```json
[
  { "id": "...", "documentId": "...", "author": "user@example.com",
    "createdAt": "2026-05-04T13:00:00", "body": "..." }
]
```

### `POST /api/v1/documents/{id}/comments`

Add a comment to the document. Body is a JSON object with a `body` string.
Returns the saved comment.

## Spans

### `PATCH /api/v1/spans/{id}`

Update a span's status, type, or both.

```json
{ "status": "APPROVED|REJECTED|PENDING", "type": "ssn" }
```

Either field may be omitted; sending neither returns `400`. `type` is validated
against the [PII types list](pii-types.md). Returns the updated `Span` object.

`409` if the parent document is in a terminal state.

### `DELETE /api/v1/spans/{id}`

Hard-delete a span. **Only manually-created spans can be deleted** — for spans
the redactor produced, flip status to `REJECTED` instead.

```json
{ "id": "...", "deleted": true }
```

`400` if the span was redactor-created. `409` if the parent document is
terminal.

### `POST /api/v1/spans/{id}/redact-like`

Find every other occurrence of the source span's text in the parent document
and approve each match with the source span's PII type. New `Span` rows are
created where matches don't already have one; existing spans at exact ranges
are flipped to `APPROVED` and aligned to the source type. Overlapping non-exact
matches are skipped to avoid duplicate spans.

Response:

```json
{ "created": 0, "approved": 0 }
```

`created` is the number of new spans inserted. `approved` is the number of
existing spans flipped to approved.

`400` if the source span has empty text. `404` if the span or its document is
missing.

## Search

### `GET /api/v1/search`

Full-text search across the OpenSearch index of ingested documents. Each
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
      "restricted": false,
      "id": "...",
      "batchId": "...",
      "filename": "...",
      "status": "AUTO_APPROVED",
      "highlights": ["… <em>match</em> snippet …"]
    }
  ]
}
```

A hit in a batch the caller can't see is returned with `"restricted": true`
and all other content fields nulled (or empty for `highlights`). This lets
clients show that a hit exists without exposing any of the document's content.

If OpenSearch is unreachable, a query returns an empty result set rather than
failing the request — search is best-effort.

## LLM-as-a-Judge

These endpoints proxy a configured Ollama instance to provide an LLM second
opinion on the redactor's output. Configuration lives under
**Admin → LLM-as-a-Judge**.

### `GET /api/v1/ollama/{instanceId}/models`

List the models installed on a configured Ollama instance.

```json
{ "instanceId": "...", "instanceName": "...", "models": ["llama3", "mistral"] }
```

`404` if the instance id is unknown. `502` if Ollama is unreachable.

### `POST /api/v1/documents/{documentId}/explain`

Ask the LLM to explain the PII risk in a document.

```json
{ "instanceId": "...", "model": "llama3" }
```

Response:

```json
{ "instanceName": "...", "model": "llama3", "response": "..." }
```

### `POST /api/v1/spans/{spanId}/second-opinion`

Ask the configured Second Opinion default Ollama instance/model whether the
named span is genuinely PII or a likely false positive. The instance and model
are chosen from the LLM-as-a-Judge defaults — the request body is empty.

```json
{ "instanceName": "...", "model": "...",
  "sourceText": "...", "sourceType": "ssn",
  "response": "..." }
```

`400` if no Second Opinion default is configured.

## Errors

Error responses have a JSON body with at least an `error` field describing the
issue. Status codes follow the table per endpoint above.
