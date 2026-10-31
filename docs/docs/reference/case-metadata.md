# Case metadata

A document can carry its court filing's case metadata. In a batch set to
choose its policy by case type, the case type decides which Philter policy
redacts the document, since court rules differ by case type.

## Schema

| Field        | Type    | Values                                                                 | Stored     |
|--------------|---------|------------------------------------------------------------------------|------------|
| `caseNumber` | string  | Up to 100 characters.                                                  | Encrypted  |
| `caseType`   | string  | Up to 64 letters, digits, spaces, hyphens, or underscores, starting with a letter or digit. Matched without regard to case; stored lower-cased. | Plain text |
| `filer`      | string  | Up to 200 characters.                                                  | Encrypted  |
| `feeStatus`  | string  | `paid`, `unpaid`, or `waived`.                                         | Plain text |
| `ifp`        | boolean | Whether the filer proceeds in forma pauperis.                          | Plain text |

Every field is optional. Values are trimmed, and a blank value is the same as
none. The case number and filer can identify people, so they are encrypted at
rest like a document's text, and audit entries carry keyed hashes of them, not
their values. The case type, fee status, and IFP flag stay readable so they can
be matched and shown.

## Supplying it

- **Upload:** the **Case (optional)** section of the upload form applies the
  values to every file in that upload. See [Adding documents](../user-guide/uploading.md).
- **REST API:** a `caseMetadata` object on
  [`POST /api/v1/ingest`](api.md#post-apiv1ingest).
- **Data sources:** for Local Directory and S3-Compatible sources, a sidecar
  file beside each document. See [Sidecar files](#sidecar-files).

Invalid metadata is refused before anything is stored: the upload shows the
problem, and the API answers `400` with a `problems` list.

### Sidecar files

A sidecar is named after its document with `.case.json` added, and holds a JSON
object with the fields above:

```text
filings/
  motion.pdf
  motion.pdf.case.json
```

```json
{"caseNumber": "1:26-cv-01234", "caseType": "civil", "filer": "Acme Dental LLC",
 "feeStatus": "paid", "ifp": false}
```

Sidecars are never imported as documents. A document without one has no case
metadata. A sidecar that is not valid JSON, has an unknown field, or has an
invalid value fails that document's import, so it is not queued with the wrong
metadata; the import log says what was wrong without quoting the values.
Sidecars are up to 16 KB.

OpenSearch, Elasticsearch, and Relational Database sources do not read case
metadata; set it on their documents through the API afterwards.

## Choosing the policy by case type

1. Map case types to policies under **Admin → General → Case types**. Each case
   type maps to one policy of the Philter user that redacts batches; mapping a
   case type again replaces its policy. Changes are audited as
   `CASE_TYPE_POLICY_CHANGE`.
2. On the batch, tick **Choose each document's policy from its case type**,
   when creating it or with **Change…** in its Policy column. The Policy column
   then shows **By case type**. The batch's own policy is kept, and used again if
   the setting is turned off.

Each document in such a batch is redacted with the policy mapped to its case
type. A document with no case type, or a case type with no mapping, is not
redacted with another policy: it is marked `FAILED` with the reason, and appears
under **Admin → Ingest Queue**. Give it a case type, or map its case type, then
retry it there.

Batches without the setting use their own policy, whatever their documents'
case types.

## The policy used

When Philter redacts a document, Philter Workspace records the policy name and
version Philter reports applying. The review page shows them beside the case
metadata, and
[`GET /api/v1/documents/{id}/case-metadata`](api.md#get-apiv1documentsidcase-metadata)
returns them.

## Changing it

**Edit case** on the review page, or
[`PUT /api/v1/documents/{id}/case-metadata`](api.md#put-apiv1documentsidcase-metadata),
replaces a document's case metadata, unless the document is finalized or still
waiting to be redacted (`PENDING` or `PROCESSING`). A change
does not redact the document again. In a batch that chooses its policy by case
type, the new case type applies when the document is redacted next, such as when
a failed document is retried.

Each change is audited as `DOCUMENT_CASE_METADATA_UPDATE`, with the previous and
new case type, fee status, and IFP flag, and keyed hashes of a changed case
number or filer.
