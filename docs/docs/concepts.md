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
| Audit Sampling Rate   | Fraction of would-be auto-approved documents pulled into review for audit (default `0.10`) |
| PII type weights      | Per-type sensitivity for the risk score (see [PII types](reference/pii-types.md))      |
| Philter instance      | Which configured Philter instance redacts the batch's documents (else embedded Phileas) |
| Policy                | Name of the Phileas policy used during redaction                                       |
| Domain                | Optional grouping tag used for reporting                                               |
| Closed                | A closed batch refuses new documents (existing ones remain reviewable)                 |

Only admins can create or close a batch. Settings (group, thresholds, weights)
can be changed at any time by anyone with access to the batch.

## Documents

A **document** belongs to a single batch. It carries the original text, a
filename, a status (see below), a numeric **risk score** between 0 and 1
that reflects how much PII is in the document weighted by sensitivity, and a
**priority** flag (`1` Low, `2` Normal, `3` High; defaults to Normal) that the
queue surfaces as a small chevron icon next to the filename.

Document statuses:

| Status            | Meaning                                                            |
| ----------------- | ------------------------------------------------------------------ |
| `PENDING`         | Awaiting redaction in the [ingest queue](#ingest-queue)            |
| `PROCESSING`      | Currently claimed by a redaction worker (transient)                |
| `REVIEW_REQUIRED` | Has at least one span the reviewer must accept or reject           |
| `AUDIT_REQUIRED`  | Eligible for auto-approval but pulled into review by audit sampling |
| `AUTO_APPROVED`   | All spans auto-accepted, no human review needed                    |
| `APPROVED`        | A reviewer explicitly approved the document                        |
| `REJECTED`        | A reviewer explicitly rejected the document                        |
| `FAILED`          | Redaction failed and the document was stored without spans         |

The queue also surfaces an `AUTO_APPROVED` *display label* for any non-terminal
document whose risk score is at or below the batch's Document Threshold,
overlaying the underlying status — retuning the threshold relabels existing
rows. Documents in `AUDIT_REQUIRED` are deliberately excluded from this
relabeling so the audit sample stays visible.

## Ingest queue

Newly received documents (whether uploaded through the UI or submitted via the
[REST API](reference/api.md)) are persisted as `PENDING` and placed on a
shared, MongoDB-backed redaction queue. A background worker drains the queue
oldest-first, claiming each document atomically (`PENDING → PROCESSING`) so
multiple replicas can run safely. Once Philter completes, the worker writes
the spans, computes the risk score, and transitions the document to its final
post-ingest status (`REVIEW_REQUIRED`, `AUDIT_REQUIRED`, or `AUTO_APPROVED`).

Admins monitor the queue at **Admin → Ingest Queue**, where they can also
remove a still-pending document.

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

## Approval rules and dual approval

A batch may optionally require **two approvals from two different reviewers**
before a document moves to `APPROVED`. Admins control this through
**rule sets** under **Admin → Approval Rules**.

### Rule sets, batches, and the AND/OR model

A batch can have **zero or more rule sets** attached to it. Each rule set is a
collection of conditions, and each condition is one of the rules listed below.

- **Within a single rule set, conditions are AND-ed.** A rule set "fires" only
  when *every* condition in it is satisfied at approve time.
- **Across rule sets on the same batch, results are OR-ed.** A document
  requires dual approval if *any* of the batch's rule sets fires.
- A batch with **no** rule sets behaves as before: single approval.

This lets you express "trigger dual approval when (A AND B) OR (C AND D)"
without complex per-rule expressions: each AND group is its own rule set, and
adding more rule sets simply adds more independent triggers.

### Available rules

| Rule | What it checks | Configurable cutoff |
| ---- | -------------- | ------------------- |
| Document contains an SSN, credit card, or passport number | Span types `SSN`, `CREDIT_CARD`, `PASSPORT_NUMBER` | — |
| Document risk score above a threshold | The document-level risk score | yes (0–1, default `0.9`) |
| A rejected span has confidence above a threshold | Any rejected span's `confidence` | yes (0–1, default `0.95`) |
| Approving reviewer has performed fewer reviews than a threshold | Reviewer's prior approve count | yes (≥0, default `100`) |
| Reviewer manually added more than X redactions | Count of `manuallyCreated` spans on the document | yes (integer ≥0, default `5`) |
| Document contains classified keywords | Case-insensitive substring match against an admin list (e.g. *Classified*, *Proprietary*, *Secret*) | yes (comma-separated list) |
| Dual-approval sampling rate | Per-document random roll persisted at ingest, compared to a configured rate | yes (0.0–1.0, default `0.02` = 2%) |

The reviewer-experience rule short-circuits at queue display time so it can
flag documents up front; document-side rules are evaluated against the live
document state.

### Behavior when a rule set fires

When dual approval is required, the document tracks an ordered list of
approver emails (`approvedBy`) and stays in `REVIEW_REQUIRED` until enough
approvals have been collected. The same reviewer can never approve a document
twice. The Document Queue's **Approvals** column shows progress (e.g. `1 of 2`).

For worked examples and recommended configurations, see the admin guide:
[Approval rule sets](admin/rules.md).

## Inbox

Each user has a per-user **Inbox** for system-delivered messages
(`inbox_messages` collection: id, userId, message, createdAt, read). The
sidebar **Inbox** link displays an unread-count badge that's available on every
page via a global controller advice. Users mark messages read individually;
admins can deliver messages programmatically via the inbox service.

## Search

Every document is indexed in **OpenSearch** at ingest time with its filename,
batch, status, and full original text. The **Search** page (sidebar) and the
`GET /api/v1/search` endpoint run a full-text match query against the
`arbiter-documents` index. Search results that fall outside the caller's group
visibility return as `restricted: true` with content fields nulled — so the
caller knows a result exists without seeing what it is.

The OpenSearch endpoint is configured under **Admin → General**
(default `http://localhost:9200`).

## Audit log

Every state-changing action — login, logout, batch changes, span and document
updates, settings changes — is written to the `audit_log` collection in
MongoDB with the actor's email, the resource touched, a timestamp, and
context-specific details. Admins can browse and export the log under
**Admin → Audit log**.
