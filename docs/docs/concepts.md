# Concepts

## Users and roles

A **user** signs in with an **email address** and a password. Each user has one
of two roles:

- **`USER`** — can view and review the batches and documents in groups they
  belong to.
- **`ADMIN`** — has full visibility (with an opt-in "Limit to my groups"
  filter) and exclusive access to administrative actions like creating batches,
  closing batches, and managing users / groups / settings.

Each user can also generate a personal **API key** for programmatic access.
API keys carry the same permissions as the owning user account.

## Groups

A **group** is a named collection of users. Every batch must be assigned to
exactly one group, and that assignment is what scopes visibility:

- A `USER` only sees batches whose group they belong to (and only the documents
  inside those batches).
- An `ADMIN` sees everything by default. The "Limit to my groups" checkbox on
  the queue and batches pages flips an admin to the same scoped view a regular
  user would see.

A group must have at least one member. Admins manage groups under
**Admin → Groups**.

## Batches

A **batch** is a container for documents. It has:

| Field                 | Meaning                                                                                |
| --------------------- | -------------------------------------------------------------------------------------- |
| Name                  | Human-readable label                                                                   |
| Group                 | Which user group can see and act on it                                                 |
| PII Threshold         | Per-span confidence floor for *auto-accepting* PII detections (default `0.8`)          |
| Document Threshold    | Risk-score ceiling for *auto-approving* documents (default `0.25`)                     |
| PII type weights      | Per-type sensitivity for the risk score (see [PII types](reference/pii-types.md))      |
| Closed                | A closed batch refuses new documents (existing ones remain reviewable)                 |

Only admins can create or close a batch. Settings (group, thresholds, weights)
can be changed at any time by anyone with access to the batch.

## Documents

A **document** belongs to a single batch. It carries the original text, a
filename, a status (see below), and a numeric **risk score** between 0 and 1
that reflects how much PII is in the document weighted by sensitivity.

Document statuses:

| Status            | Meaning                                                            |
| ----------------- | ------------------------------------------------------------------ |
| `PENDING`         | Awaiting redaction                                                 |
| `REVIEW_REQUIRED` | Has at least one span the reviewer must accept or reject           |
| `AUTO_APPROVED`   | All spans auto-accepted, no human review needed                    |
| `APPROVED`        | A reviewer explicitly approved the document                        |
| `REJECTED`        | A reviewer explicitly rejected the document                        |
| `FAILED`          | Redaction failed and the document was stored without spans         |

The queue also surfaces an `AUTO_APPROVED` *display label* for any non-terminal
document whose risk score is at or below the batch's Document Threshold,
overlaying the underlying status — retuning the threshold relabels existing
rows.

## Spans

A **span** is a single PII detection inside a document. Each span has:

- A **type** (e.g., `ssn`, `phone-number`, `email-address`) — see
  [PII types](reference/pii-types.md).
- A **confidence** between 0 and 1 from the redactor.
- A **status**: `PENDING` (needs review), `APPROVED` (will be redacted),
  or `REJECTED` (will be left as-is).
- A character `start`/`end` and PDF coordinates if applicable.

When a document is created, each span's initial status is set automatically
based on the batch's PII Threshold:

- `confidence ≥ PII Threshold` → `APPROVED`
- `confidence <  PII Threshold` → `PENDING`

Reviewers can later flip a span's status, change its type, or use **Redact All
Like This** to apply the same decision to every other occurrence of the exact
text within the document.

## Risk score

Each document's risk score is computed from its spans, the batch's per-PII-type
weights, the number of unresolved spans (those still `PENDING`), and the
document's word count. The exact formula and an example are on the
[Risk score reference page](reference/risk-score.md).

## Audit log

Every state-changing action — login, logout, batch changes, span and document
updates, settings changes — is written to the `audit_log` collection in
MongoDB with the actor's email, the resource touched, a timestamp, and
context-specific details. Admins can browse and export the log under
**Admin → Audit log**.
