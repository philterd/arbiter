# Uploading documents

The **Upload** page (`/upload`) accepts a single document at a time and posts
it to the redaction service.

## What you can upload

- Plain text (`.txt`)
- PDF (`.pdf`, searchable text — scanned-image PDFs are not OCR'd)

Other formats are not supported through the web UI.

## Steps

1. Pick a **Batch** from the dropdown. Only batches you have access to and
   that are **open** are shown — closed batches do not accept new documents
   and will not appear here.
2. Choose the file with **Document**.
3. Click **Redact Document**.

The document is **queued** for redaction and Arbiter immediately returns to
the upload page with a confirmation message. You'll see the document in the
[Document Queue](queue.md) once a background worker has finished processing it.

## What happens behind the scenes

When you click Redact Document, Arbiter:

1. Persists a `Document` row in the chosen batch with status `PENDING` and
   stores the original bytes (text inline; PDFs in a sidecar collection).
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

## Errors you might see

- **"Selected batch no longer exists."** — The batch was deleted or you don't
  have access to it. Pick a different one.
- **"Batch \"X\" is closed and cannot accept new documents."** — Pick an open
  batch or ask an administrator to open one for your group.
- **"No batches exist yet."** — No batch is available in any group you belong
  to. Ask an administrator to create one.

To upload large volumes of documents programmatically, use the
[REST API](../reference/api.md) instead.
