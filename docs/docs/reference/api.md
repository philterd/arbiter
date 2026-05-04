# REST API

Arbiter exposes a JSON-over-HTTP API under `/api/v1`. All endpoints require
authentication via a personal **API key** sent as a Bearer token. The API
key carries the same role and group permissions as the user that owns it.

## Authentication

Generate an API key from [Personal settings](../user-guide/settings.md). Send
it on every request:

```http
Authorization: Bearer <your-api-key>
```

Arbiter stores only the SHA-512 hash of the key. The plaintext value is shown
once at generation and cannot be recovered. Rotate by generating a new key
(which replaces the old one) or revoking the existing one.

Failed authentication (no header, malformed header, or unknown key) returns
the same response as an unauthenticated browser request: `401 Unauthorized`.

## Document ingestion

### `POST /api/v1/ingest`

Submit a plain-text document for redaction.

```json
{
  "batchId": "string",
  "name":    "string",
  "text":    "string"
}
```

| Status | Meaning                                                                |
| ------ | ---------------------------------------------------------------------- |
| `202`  | Accepted; body `{"taskId": "..."}`. Redaction runs asynchronously.     |
| `400`  | `batchId` does not exist.                                              |
| `403`  | Caller does not have access to that batch.                             |
| `409`  | Batch is closed; body includes `"closed": true`.                       |

The returned `taskId` is the document's id. Poll the queue endpoint or fetch
spans directly to track its progress.

## Triage

### `GET /api/v1/queue`

List documents the caller can see, paged by risk score (highest first).

| Query param      | Default     | Meaning                                                |
| ---------------- | ----------- | ------------------------------------------------------ |
| `page`           | `0`         | Zero-indexed page                                      |
| `size`           | `10`        | Page size                                              |
| `batchId`        | —           | Filter to one batch                                    |
| `status`         | —           | Filter to one status                                   |
| `myGroupsOnly`   | `true`      | Admins can pass `false` to see every batch's documents |

Response is a Spring `Page<Map>` shape:

```json
{
  "content": [
    {
      "id": "string",
      "filename": "string",
      "status": "PENDING|REVIEW_REQUIRED|AUTO_APPROVED|APPROVED|REJECTED|FAILED",
      "riskScore": 0.0,
      "batchId": "string",
      "batchName": "string",
      "autoApproved": false,
      "documentThreshold": 0.25
    }
  ],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 10
}
```

`autoApproved` is true when the document's risk score is at or below
`documentThreshold` and the document is not in a user-decided terminal
state (`APPROVED`, `REJECTED`, `FAILED`).

### `GET /api/v1/batches`

List batches the caller can target. Honors the same `myGroupsOnly` query
param. Returns a JSON array of `{id, name}`.

### `GET /api/v1/documents/{id}/spans`

Return every `Span` row in the document. Useful for building a custom review
client or for reconciling the redactor's output with downstream systems.

## Spans

### `PATCH /api/v1/spans/{id}`

Update a span's status, type, or both.

```json
{ "status": "APPROVED|REJECTED|PENDING", "type": "ssn" }
```

Either field may be omitted; sending neither returns `400`. `type` is
validated against the [PII types list](pii-types.md). Returns the updated
`Span` object as JSON.

### `POST /api/v1/spans/{id}/redact-like`

Find every other occurrence of the source span's text in the parent document
and approve each match with the source span's PII type. New `Span` rows are
created where matches don't already have one; existing spans at exact ranges
are flipped to `APPROVED` and aligned to the source type. Overlapping
non-exact matches are skipped to avoid duplicate spans.

Response:

```json
{ "created": 0, "approved": 0 }
```

`created` is the number of new spans inserted. `approved` is the number of
existing spans flipped to approved.

`400` if the source span has empty text. `404` if the span or its document is
missing.

## Errors

Error responses have a JSON body with at least an `error` field describing
the issue. Status codes follow the table per endpoint above.
