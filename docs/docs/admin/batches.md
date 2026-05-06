# Batches

Batches live at `/batches`. The page shows every batch you have access to,
sorted by date created (newest first by default). The **Name**, **Created**,
and **Documents** columns are click-to-sort headers — click again to flip
direction. Additional columns include **Queued** (count of non-terminal
documents) and a **Review** link/badge that opens the first reviewable
document in the batch.

## Create a batch (admin only)

A card at the top of the page lets administrators create a batch. Fill in:

| Field                | Default | Notes                                                                                      |
| -------------------- | ------- | ------------------------------------------------------------------------------------------ |
| Name                 | —       | Required                                                                                   |
| Group                | —       | Required; only existing groups appear in the dropdown                                      |
| Philter instance     | default | Pick the Philter that redacts this batch's documents                                       |
| Policy               | —       | A policy name that exists on the chosen Philter instance                                   |
| Batch Finalization Policy | —  | Required; governs document retention when the batch is finalized                           |
| Compliance Profile   | —       | Required; **cannot be changed after the batch is created**                                 |
| Domain               | —       | Optional grouping tag used in the Reports page aggregates                                  |
| PII Threshold        | `0.80`  | Per-span confidence floor for auto-accepting detections                                    |
| Document Threshold   | `0.25`  | Risk-score ceiling for auto-approving whole documents                                      |
| Audit Sampling Rate  | `0.10`  | Fraction of would-be auto-approved documents pulled into review                            |

Non-admins do not see the create card. The endpoint refuses non-admin POSTs.

## Per-row actions

Each batch row has:

- An inline **Group** selector with a **Save** button (changes the assigned
  group; non-admins are limited to groups they belong to).
- A **Settings** button that opens a modal to edit the PII Threshold,
  Document Threshold, **and Audit Sampling Rate**. All three are validated to
  `[0.0, 1.0]`.
- A **Weights** link that opens the per-batch PII weight editor (see below).
- A **Close** button (admin only) that marks the batch closed.
- A **Closed** badge next to the name when the batch is in the closed state.

Only admins can create or close a batch. Approval rules for each batch are
managed separately under **Admin → Approval Rules**; see
[Approval rule sets](rules.md) for the AND-within / OR-across model and
worked examples. All other settings (group, thresholds, weights) remain
editable for users with access to the batch.

## Closing a batch

Closing is one-way. A closed batch:

- Refuses new uploads from the web UI (it disappears from the upload page's
  batch dropdown, and direct POSTs to `/redact` are rejected).
- Returns **HTTP 409** from the API ingestion endpoint with a body like
  `{"error": "Batch \"<name>\" is closed and cannot accept new documents.",
  "batchId": "...", "closed": true}`.
- Stays fully visible in the queue and review UI — existing documents in the
  batch remain reviewable, exportable, and modifiable.
- Records a `BATCH_CLOSE` audit entry.

There is no built-in reopen action.

## PII weights (`/batches/{id}/weights`)

Each batch has its own per-PII-type sensitivity weight. The defaults are:

| Type              | Default weight |
| ----------------- | -------------- |
| `ssn`             | 10             |
| `credit-card`     | 10             |
| `phone-number`    | 5              |
| `email-address`   | 5              |
| `person`          | 3              |
| `first-name`      | 3              |
| `surname`         | 3              |
| `physician-name`  | 3              |
| `street-address`  | 3              |
| `zip-code`        | 2              |
| Everything else   | 1              |

The weights page lists every supported PII type alphabetically. The displayed
**Weight** column starts at the effective value (default unless overridden
for this batch). Set any value to `0` to ignore that type entirely. **Save
weights** persists overrides; only values that differ from the default are
stored, so re-tuning a single default later picks up automatically for any
batch that didn't override it. **Reset to defaults** clears all overrides.

Higher weights cause spans of that type to contribute more to a document's
risk score. The full formula is on
[the Risk score reference page](../reference/risk-score.md).

## Visibility scope (admin checkbox)

Admins see a **Limit to my groups** checkbox at the top of the page. With
it checked (the default), the list shows only batches in groups the admin
belongs to. Unchecking it shows every batch system-wide. The selection is
preserved across sort clicks and reflected in the URL via `?myGroupsOnly=…`.
