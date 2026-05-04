# Arbiter

Arbiter is a human-in-the-loop deidentification tool. It runs documents through
the [Philter / Phileas](https://www.philterd.ai) PII detector, then gives
reviewers a UI to confirm or correct each detection before the document is
considered redacted.

## What Arbiter does

- **Ingests** plain-text and PDF documents through a web upload form or a REST
  API.
- **Detects PII** in each document and stores every detection as a *span* with a
  type (SSN, phone-number, etc.), confidence, and character offsets.
- **Scores risk** for each document using a configurable, weighted formula that
  combines span confidence, PII-type sensitivity, and a length-aware penalty for
  unresolved detections.
- **Auto-approves** documents whose risk score is below a per-batch threshold,
  so reviewers focus only on the documents that need human eyes.
- **Lets reviewers** accept, refuse, change the type of, or bulk-redact every
  occurrence of a span — all from a single side-by-side review pane.
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
  [Queue](user-guide/queue.md), [Uploading](user-guide/uploading.md),
  [Reviewing](user-guide/reviewing.md),
  [Personal settings](user-guide/settings.md).
- **Admin guide** —
  [Users and Groups](admin/users-and-groups.md),
  [Batches](admin/batches.md),
  [Audit log](admin/audit-log.md),
  [Notifications](admin/notifications.md).
- **Reference** —
  [Risk score formula](reference/risk-score.md),
  [PII types and default weights](reference/pii-types.md),
  [REST API](reference/api.md).
- **[Security](security.md)** — authentication, authorization, password and
  API-key storage.
