# Arbiter

Arbiter is a human-in-the-loop deidentification tool. It runs documents through
the [Philter / Phileas](https://www.philterd.ai) PII detector, then gives
reviewers a UI to confirm or correct each detection before the document is
considered redacted.

## What Arbiter does

- **Ingests** plain-text and PDF documents through a web upload form or a REST
  API. Uploads are queued and a background worker drains the queue oldest-first;
  multiple Arbiter replicas can run side by side and won't pick up the same
  document twice. Admins watch the
  queue at **Admin → Ingest Queue**. Admins can also register external
  **[data sources](admin/data-sources.md)** — OpenSearch, Amazon S3, a
  relational database, or a local directory — that surface as ingest options
  on the Add Documents page.
- **Detects PII** in each document and stores every detection as a *span* with a
  type (SSN, phone-number, etc.), confidence, and character offsets.
- **Scores risk** for each document using a configurable, weighted formula that
  combines span confidence, PII-type sensitivity, and a length-aware penalty for
  unresolved detections.
- **Auto-approves** documents whose risk score is below a per-batch threshold,
  so reviewers focus only on the documents that need human eyes — with a
  configurable **audit sampling rate** that randomly pulls a fraction of those
  back for spot-checks.
- **Optionally requires dual approval** per batch — admins choose conditions
  under **Approval Rules**, and matching documents need approvals from two
  different reviewers before they move to `APPROVED`.
- **Lets reviewers** accept, refuse, change the type of, or bulk-redact every
  occurrence of a span — all from a single side-by-side review pane.
- **Indexes the full text** of every ingested document into OpenSearch so
  reviewers (and the API) can run free-text searches; results in batches the
  caller can't see are masked rather than dropped.
- **Notifies users** through a per-user **Inbox** for system messages, with an
  unread-count badge on the sidebar.
- **Audits everything**: every action (login, batch change, span update,
  document approval, settings change) is recorded with user, resource, and
  timestamp, and admins can export filtered slices as JSON or CSV.

## Who's it for

- **Reviewers** triage uploaded documents from a queue, opening each one for
  span-by-span review and approval.
- **Administrators** create batches, assign them to user groups, tune
  PII-detection thresholds and per-type weights, manage users and groups,
  configure SMTP, and access the audit log.

## How the docs are organized

- **[Getting started](getting-started.md)** — install, configure, and sign in
  for the first time.
- **[Concepts](concepts.md)** — the model behind users, groups, batches,
  documents, spans, and risk scores.
- **User guide** — the day-to-day reviewer workflow:
  [Queue](user-guide/queue.md),
  [Adding documents](user-guide/uploading.md),
  [Reviewing](user-guide/reviewing.md),
  [Personal settings](user-guide/settings.md).
- **Admin guide** —
  [Users and Groups](admin/users-and-groups.md),
  [Batches](admin/batches.md),
  [Data sources](admin/data-sources.md),
  [Audit log](admin/audit-log.md),
  [Notifications](admin/notifications.md).
- **Reference** —
  [Risk score formula](reference/risk-score.md),
  [PII types and default weights](reference/pii-types.md),
  [REST API](reference/api.md) (covers ingest, search, comments, LLM-judge,
  finalize/audit, and span CRUD).
- **[Security](security.md)** — authentication, authorization, password and
  API-key storage.
