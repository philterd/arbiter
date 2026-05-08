# Adding documents

The **Add Documents** page (`/upload`) is reachable from the **Add Documents**
link in the sidebar's Redaction section. It supports several ways to feed
documents into Arbiter; pick the matching tab at the top of the page:

- **Upload files** — submit a single document from your machine (the
  long-standing default).
- **OpenSearch**, **Elasticsearch** — pick a registered
  [data source](../admin/data-sources.md) and queue every document its query
  returns. Both ingest paths are fully implemented; see
  [Adding from OpenSearch or Elasticsearch](#adding-from-opensearch-or-elasticsearch).
- **Amazon S3**, **Relational Database**, **Local Directory** — visible
  for completeness but not yet implemented; submitting one of these forms
  returns a *"…is not yet implemented"* notice.

## What you can upload (Upload files tab)

- Plain text (`.txt`)
- PDF (`.pdf`, searchable text — scanned-image PDFs are not OCR'd)

Other formats are not supported through the web UI.

## Steps (Upload files tab)

1. Pick a **Batch** from the dropdown. Only batches you have access to and
   that are **open** are shown — closed batches do not accept new documents
   and will not appear here.
2. Pick a **Priority**:
    - **1 — Low**
    - **2 — Normal** (default)
    - **3 — High**

   Priority is a hint for reviewers, not a queueing weight: redaction itself
   still drains the ingest queue oldest-first. The chosen priority is recorded
   on the document and shown as a chevron icon next to the filename in the
   [Document Queue](queue.md#priority-column) so high-priority items stand out.
3. Choose the file with **Document**.
4. Click **Upload Document**.

The document is **queued** for redaction and Arbiter immediately returns to
the upload page with a confirmation message. You'll see the document in the
[Document Queue](queue.md) once a background worker has finished processing it.

## What happens behind the scenes

When you click Upload Document, Arbiter:

1. Persists a `Document` row in the chosen batch with status `PENDING` and
   stores the original bytes (text inline; PDFs in a sidecar collection).
   A SHA-512 hash of the original content is also recorded on the document
   for chain-of-custody and tamper detection — see
   [Security · Document content integrity](../security.md#document-content-integrity).
2. Returns control to the browser — your upload is on the queue.

Independently, a background worker drains the queue oldest-first. For each
claimed document it:

1. Calls Philter with the original content.
2. Creates a `Span` row for each PII detection. Each span's initial status is
   set from the batch's **PII Threshold**:
    - confidence ≥ threshold → `APPROVED` (auto-accepted)
    - confidence < threshold → `PENDING` (needs review)
3. Computes the document's **risk score** using the batch's per-PII-type
   weights and the count of unresolved (`PENDING`) spans.
4. Sets the document's final status:
    - Any `PENDING` span → `REVIEW_REQUIRED`
    - Otherwise the worker rolls the batch's **Audit Sampling Rate**:
        - sampled in → `AUDIT_REQUIRED`
        - not sampled → `AUTO_APPROVED`

If the document's risk score is at or below the batch's Document Threshold and
the document isn't in `AUDIT_REQUIRED`, the queue will show it as
**`AUTO_APPROVED`** even before any human review.

Admins can monitor the queue at **Admin → Ingest Queue** and remove
still-pending documents from there.

## Adding from a data source

All data-source tabs share the same shape:

1. Pick a **Batch** and a **Priority** as above.
2. Pick a registered **source** from the dropdown. Each option is labeled with
   the connection details an admin set on the
   [Data Sources page](../admin/data-sources.md), so you can tell sources
   apart.
3. Click the **Ingest from …** button.

If no source of that type is registered, the panel shows a yellow notice with
a link to the Data Sources admin page (admins) or instructions to ask an admin
(reviewers).

### Adding from OpenSearch or Elasticsearch

Both ingest paths run as a **background job**, so the page returns
immediately and you can watch progress while the worker pulls documents in.

1. Pick a **Batch**, a **Priority**, and the **OpenSearch source** (or
   Elasticsearch source) the admin registered.
2. Click **Ingest from OpenSearch** (or **Ingest from Elasticsearch**).
3. Arbiter redirects you to the **Background Jobs** page (Redaction sidebar
   group → *Background Jobs*) with a confirmation banner. The new job
   starts in `PENDING`, transitions to `RUNNING`, and ends in `COMPLETED`
   or `FAILED`.

The worker uses the cluster's scroll API and pulls **at most 100 hits per
page**, paging until no more results remain — so even very large queries
can be ingested. For each hit it:

- Reads the configured **Text field** from the hit's `_source` and queues
  that text as a new document on the chosen batch. If the field is missing
  or null, the hit counts as failed and the reason is recorded for the job.
- Uses the configured **Filename field** (when set) for the imported
  document's filename, falling back to the hit's `_id`.
- Records traceability — the source system, cluster URL, index, the
  `_id` of the source document, and the **import timestamp** — onto the
  Document, where it appears on the **Document Information** popup of the
  Review page.

#### Background Jobs page

The Background Jobs page (`/jobs`) auto-refreshes every 10 seconds and
shows one row per job:

| Column        | What it shows                                                                                                             |
| ------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Type          | `OpenSearch Ingest` or `Elasticsearch Ingest`                                                                              |
| Source        | Name of the data source the job is pulling from                                                                            |
| Batch         | Batch the imported documents are landing in                                                                                |
| Status        | `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` (hover the FAILED badge for the top-level error)                            |
| Progress      | `processed of total` (when the cluster reports a total) or `N processed`. Failed counts include a *Show failure details* disclosure listing per-hit reasons. |
| Details       | Blue **Details** link that opens a popup with Started/Finished timestamps, Started by, and the per-outcome counts (Successful, Failed, Skipped). |

Reviewers see only their own visibility scope: a non-admin sees a job only
when its batch is in one of their groups; admins see every job.

If a hit is dropped (missing text field, server error, etc.) the job
continues with the rest of the page; **partial success is preserved** —
the documents that did import are already on the redaction queue.

Re-running an import never duplicates content. When the worker encounters
an `(_index, _id)` pair already present in MongoDB, the hit is recorded
as **Skipped** — a placeholder Document row with status `SKIPPED` is
written for the audit trail but no new content is enqueued. See
[Background Jobs · Skipped (already-imported) documents](background-jobs.md#skipped-already-imported-documents).

## Errors you might see

- **"Selected batch no longer exists."** — The batch was deleted or you don't
  have access to it. Pick a different one.
- **"Batch \"X\" is closed and cannot accept new documents."** — Pick an open
  batch or ask an administrator to open one for your group.
- **"No batches exist yet."** — No batch is available in any group you belong
  to. Ask an administrator to create one.

To upload large volumes of documents programmatically, use the
[REST API](../reference/api.md) instead.
