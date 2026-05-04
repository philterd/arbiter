# Workflow

This page walks through how Arbiter is used end-to-end: how an administrator
sets it up, how documents flow through it, and how reviewers approve or reject
the redactions Arbiter proposes. If you have not yet read
[Concepts](concepts.md), start there for the vocabulary used below.

## The big picture

Arbiter is a **human-in-the-loop deidentification reviewer**. It does not
detect PII on its own — that is Philter's job. Arbiter coordinates the work:

1. **An admin configures a Philter instance** Arbiter can call (or chooses the
   built-in *Embedded Philter*).
2. **An admin authors policies.** A policy tells Philter what counts as PII
   and how to redact each type.
3. **An admin creates a batch** for a group of related documents and points it
   at a Philter instance and a policy.
4. **A reviewer uploads documents** to the batch. Each document is sent to the
   batch's Philter instance using its policy. Detected PII is stored as
   *spans*.
5. **A reviewer works the queue.** Documents that need attention surface at
   the top, sorted by risk score. Each document is opened in the side-by-side
   review UI.
6. **The reviewer accepts or rejects each span**, then approves or rejects
   the document as a whole. Decisions are captured in the audit log.

Everything in Arbiter is built around that flow.

## 1. Configure Philter

A **Philter instance** is the redaction engine Arbiter calls to find PII.
Each batch is associated with exactly one Philter instance.

There are two kinds:

- **Embedded Philter** — Arbiter ships with the Phileas library bundled inside
  the same JVM. There is nothing to install or configure: it is always
  available, always reachable, and runs as part of the Arbiter process. It
  uses the built-in policies stored in Arbiter's MongoDB. It is the right
  choice for development, demos, and small single-node deployments.
- **External Philter** — a separately running [Philter](https://philterd.ai)
  service that Arbiter reaches over HTTP. Use this for production
  deployments, for shared instances across multiple Arbiters, when policies
  are managed centrally on the Philter server, or when the redactor needs to
  scale independently of the Arbiter web tier.

You can register any number of external Philter instances. A batch picks one
of them (or Embedded Philter) at create time, and that choice can be changed
later.

### Registering an external Philter (admin)

1. Sign in as an administrator.
2. Go to **Admin → Philter**.
3. Use **Add Philter instance** and fill in:
    - **Name** — a unique label (e.g. `philter-prod`).
    - **Endpoint** — host or IP (e.g. `philter` or `192.168.1.20`).
    - **Port** — the Philter port (default `8080`).
4. Click **Add**. The instance is saved to MongoDB.
5. Click **Test** on the row to confirm Arbiter can reach it. The test hits
   the instance's `/api/status` endpoint and reports the HTTP response.
6. Optionally set the row as the **default** so newly created batches start
   with it pre-selected. The Embedded Philter is the default if no other
   default is set.

Removing an instance is allowed even if batches reference it; affected
batches will fail to redact until they are reassigned to another instance or
to Embedded Philter.

### Default-instance pre-selection

The "Default Philter instance" setting on **Admin → Philter** controls which
option is pre-selected in the **Create batch** dropdown. It does not force
all batches to use that instance — each batch makes its own choice.

## 2. Create PII redaction policies

A **policy** is a JSON document Philter uses to identify and redact PII. It
declares which PII types to look for and what to do with each
(`REDACT`, `MASK`, `REPLACE`, etc.). For the policy schema and examples, see
the [Philter documentation](https://philterd.github.io/philter/).

Where the policy lives depends on the Philter instance:

- **Embedded Philter** — policies live in Arbiter's MongoDB `policies`
  collection. Manage them under **Policies** in the left-side menu.
- **External Philter** — policies live on the Philter server. Arbiter can
  list them (read-only) by calling the Philter instance's `/api/policies`
  endpoint, but creation, edit, and delete must happen on the Philter server.

The Policies page presents both modes with the same UI:

1. Click **Policies** in the left-side menu.
2. Choose a Philter instance from the dropdown — **Embedded Philter** is the
   default option.
3. The table lists the policies available on that instance.
4. For Embedded:
    - Use **Add policy** to register a new one. The JSON is validated before
      saving. Names are unique (case-insensitive).
    - **View** opens any policy's JSON in a popup.
    - **Edit** opens the JSON in an editable popup. The policy name is shown
      as read-only — once a policy exists, its name cannot be renamed via the
      UI or the API.
    - **Remove** deletes the policy. A policy that is currently referenced by
      one or more batches cannot be deleted; the error names the offending
      batches.
5. For external Philter instances the table is read-only (View only).

Arbiter's demo data seeds a policy named **Default** in the Embedded Philter
collection so the system is usable out of the box.

## 3. Create a batch

A **batch** is the unit of organisation in Arbiter. Every document belongs
to exactly one batch. A batch carries:

| Field                | Purpose                                                              |
| -------------------- | -------------------------------------------------------------------- |
| Name                 | Human-readable label (unique).                                       |
| Group                | Which user group can see and act on this batch (visibility scope).   |
| Philter instance     | Which Philter instance redacts documents in this batch.              |
| Policy               | Which policy on that Philter instance to apply.                      |
| PII Threshold        | Per-span confidence floor for auto-accepting detections.             |
| Document Threshold   | Risk-score ceiling for auto-approving whole documents.               |
| PII type weights     | Per-type sensitivity used in the risk score.                         |
| Closed flag          | A closed batch refuses new documents.                                |

Only administrators can create, modify, or close a batch. To create one:

1. Sign in as an administrator and open **Batches**.
2. Fill out **Create batch**:
    - **Name** and **Group** are required.
    - **Philter** defaults to Embedded Philter (or to the system default if
      one is configured).
    - **Policy** populates dynamically based on the chosen Philter instance.
      Selecting a different Philter reloads the policy list.
    - **PII Threshold** (default `0.80`) — spans whose confidence is at or
      above this value are auto-accepted. Lower values give the redactor
      more autonomy; higher values send more spans to the reviewer.
    - **Document Threshold** (default `0.25`) — documents whose risk score
      is at or below this value are auto-approved without human review.
3. Click **Create**.

After creation, anyone with access to the batch can adjust the group,
thresholds, and weights. The Philter instance and policy can be changed
together via the **Change…** link in the **Philter / Policy** column —
choosing a Philter reloads the policy list inside the modal so the two
stay in sync. Only admins can close a batch.

For more on batch settings see the
[Batches admin guide](admin/batches.md).

## 4. Upload documents

Documents enter Arbiter through one of two paths.

### Through the web UI

1. Click **Upload** in the left-side menu.
2. Choose a **Batch** from the dropdown — only open batches in groups you
   belong to are listed. The "Create a new batch." link below the dropdown
   takes you to the Batches page if you need a new one.
3. Choose a file with **Document**. Supported formats are `.txt` and
   searchable PDF (`.pdf`). Scanned PDFs are not OCR'd.
4. Click **Redact Document**.

Arbiter sends the file to the batch's Philter instance using the batch's
policy, persists the document, creates a span for each detection, and
computes the document's risk score. You then land on a preview page where
you can download the redacted text or PDF.

### Through the REST API

Programmatic ingestion uses `POST /api/v1/ingest` with a JSON body
containing the batch ID, a filename, and the text. Authentication is by
session or by API key issued from **Personal settings**. See the
[REST API reference](reference/api.md).

The API path is admin-or-group-member-only; it returns HTTP 409 when the
batch is closed.

### What happens after upload

For each document:

1. Spans are extracted by Philter, scored by confidence.
2. Each span's initial status is set from the batch's PII Threshold:
    - `confidence ≥ threshold` → `APPROVED` (auto-accepted).
    - `confidence < threshold` → `PENDING` (needs review).
3. The document's risk score is computed using the batch's PII weights and
   the count of unresolved spans.
4. The document's status is set:
    - No spans → `AUTO_APPROVED`.
    - Any `PENDING` span → `REVIEW_REQUIRED`.
    - Otherwise → `AUTO_APPROVED`.
5. The document appears in the queue.

A document whose risk score is at or below the batch's Document Threshold
is shown with the `AUTO_APPROVED` label even before human review;
re-tuning the threshold relabels existing rows on the next page load.

For more detail see [Uploading documents](user-guide/uploading.md).

## 5. Work the queue

The **Queue** at `/queue` is the reviewer's home page. It lists every
document the user has access to, ordered by risk score (highest first), so
the riskiest material lands at the top.

A reviewer typically:

1. Optionally filters by **Batch** or **Status** — `REVIEW_REQUIRED` first if
   you want to focus on documents needing attention.
2. Clicks **Review** on a row to open the side-by-side review UI.
3. Works through pending spans (see the next section).
4. Approves or rejects the document.
5. Returns to the queue and repeats.

Admins see an extra **Limit to my groups** checkbox that toggles between
their group-scoped view and a global view. Pagination is 10 documents per
page. For a column-by-column reference see [Queue](user-guide/queue.md).

## 6. Review a document

The review page (`/review/{id}`) has three panes:

1. **Original** — the source text with each detected span highlighted.
2. **Redacted** — the same text with each accepted span replaced by a
   `<<TYPE>>` marker.
3. **PII Navigator** — one entry per span, sorted by position.

The Original and Redacted panes scroll together. Clicking a span anywhere
focuses the matching navigator entry and vice versa.

### Per-span actions

Each navigator entry exposes:

- A **type pill** that doubles as a dropdown to change the detected type
  (e.g. flip a span from `ssn` to `phone-number`).
- The matched **text** and the redactor's **confidence**.
- A status pill — **Accepted** or **Refused** — that toggles on click. A
  refused span is removed from the Redacted pane and struck through in the
  navigator.
- **Redact All Like This** — finds every other case-sensitive occurrence
  of the exact text in the document and updates them to match. Useful for
  unambiguous strings (project names, email addresses); use with care on
  short or substring-prone text.

### Document-level actions

The header has **Approve**, **Reject**, and (for already-approved
documents) **Unapprove**:

- **Approve** moves the document to `APPROVED` and returns to the queue.
- **Reject** moves the document to `REJECTED` and returns to the queue.
- **Unapprove** flips an approved document back to `REVIEW_REQUIRED`.

Approve / Reject are hidden once the document is in a terminal state
(`APPROVED`, `REJECTED`, `FAILED`). Every action is captured in the audit
log with the actor, timestamp, and the change made.

For more detail see [Reviewing a document](user-guide/reviewing.md).

## A typical end-to-end run

Putting all the pieces together:

1. **Admin** stands up Arbiter and MongoDB. Embedded Philter is available
   immediately.
2. **Admin** opens **Admin → Philter**, optionally registers an external
   Philter for production use, and clicks **Test** to confirm reachability.
3. **Admin** opens **Policies**, picks Embedded Philter (or the new external
   instance), and reviews the Default policy. They edit it or add a new one
   tuned to their data.
4. **Admin** creates a user group under **Admin → Groups** and adds the
   relevant reviewers to it.
5. **Admin** opens **Batches**, creates `Q3-onboarding-forms`, picks the
   group, picks the Philter instance, picks a policy, and accepts the
   default thresholds.
6. **Reviewer** signs in, lands on the **Dashboard**, and sees the new batch
   under "Manage batches" and the documents-needing-review widget.
7. **Reviewer** uses **Upload** to send a document into the batch — it lands
   in the queue with status `REVIEW_REQUIRED` and a non-zero risk score.
8. **Reviewer** opens the queue, clicks **Review**, accepts the spans that
   are correct, refuses or retypes the ones that are wrong, and clicks
   **Approve**.
9. The document moves to `APPROVED`. Risk score, span decisions, and the
   approval are now in the audit log.
10. When `Q3-onboarding-forms` is finished, **Admin** clicks **Close** on
    the batch to lock it against new uploads. Existing documents in the
    batch stay reviewable forever.

## Where to go next

- [Concepts](concepts.md) — the underlying vocabulary.
- [Uploading documents](user-guide/uploading.md), [Queue](user-guide/queue.md),
  [Reviewing a document](user-guide/reviewing.md) — page-by-page reference
  for reviewers.
- [Batches](admin/batches.md), [Users and groups](admin/users-and-groups.md),
  [Audit log](admin/audit-log.md) — admin reference.
- [Risk score](reference/risk-score.md), [PII types](reference/pii-types.md),
  [REST API](reference/api.md) — reference material.
