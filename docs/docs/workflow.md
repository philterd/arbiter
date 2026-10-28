# Workflow

This page walks through how Arbiter is used end-to-end: how an administrator
sets it up, how documents flow through it, and how reviewers approve or reject
the redactions Arbiter proposes. If you have not yet read
[Concepts](concepts.md), start there for the vocabulary used below.

## The big picture

Arbiter is a **human-in-the-loop deidentification reviewer**. It does not
detect PII on its own — that is Philter's job. Arbiter coordinates the work:

1. **An admin connects Arbiter to its Philter**, in Arbiter's environment.
2. **An admin authors policies in Philter.** A policy tells Philter what counts
   as PII and how to redact each type.
3. **An admin creates a batch** for a group of related documents and chooses
   its policy.
4. **A reviewer uploads documents** to the batch. Each document is sent to
   Philter with the batch's policy. Detected PII is stored as *spans*.
5. **A reviewer works the queue.** Documents that need attention surface at
   the top, sorted by risk score. Each document is opened in the side-by-side
   review UI.
6. **The reviewer accepts or rejects each span**, then approves or rejects
   the document as a whole. Decisions are captured in the audit log.

Everything in Arbiter is built around that flow.

## 1. Configure Philter

Arbiter works with one [Philter](https://philterd.ai), 4.0.0 or later. It signs
people in through it, lists its policies,
and sends every batch's documents to it for redaction. Arbiter does not detect
PII itself.

### Connecting Arbiter to Philter

Set two environment variables on Arbiter:

| Variable | Purpose |
|---|---|
| `ARBITER_PHILTER_URL` | Philter's `http` or `https` URL, such as `http://philter:8080`. |
| `ARBITER_PHILTER_API_KEY` | The API key Arbiter uses for background work such as ingest, sent as a bearer token. It must not belong to a Philter administrator; see [Philter API key for Arbiter](#philter-api-key-for-arbiter). |

Both are required: Arbiter does not start without them. For HTTPS with a
private certificate authority, or mutual TLS, set the `arbiter.philter.tls.*`
settings in
[Philter integration](reference/configuration.md#philter-integration). The URL
and key are not admin settings, so they cannot be changed from the UI.

**Admin → Philter** shows the URL and whether the key belongs to a Philter
administrator. **Test** calls Philter's `/api/health` endpoint and reports its
Philter version, then makes an authenticated call to confirm Philter accepts the
key and to check its role. Arbiter also checks the role when it starts, and logs
a warning for an administrator's key. The key keeps working, but the page shows
a warning until it is replaced. Each Test is recorded in the audit log as
`PHILTER_TEST`.

### Philter API key for Arbiter

Philter keeps policies, contexts and their consistent-pseudonymization
mappings, and the default format-preserving encryption key per user: a key
reaches only its own user's. Every batch therefore uses the policies and
contexts of the user who owns `ARBITER_PHILTER_API_KEY`.

That key must not belong to a Philter administrator. Arbiter asks Philter for
the key's role (`GET /api/limits`) when it starts and on **Test**, which fails
for an administrator's key. Such a key keeps working, but **Admin → Philter**
shows a warning until the key's user is no longer an administrator.

`docker-compose.prod.yaml` does not publish Philter's port, so the commands below
run `curl` in a container on the compose network. `docker network ls` shows its
name, `<project>_arbiter`; it is `arbiter_arbiter` when the compose project is
named `arbiter`. Set `PHILTER_ADMIN_KEY` to an administrator's key, such as
`PHILTER_BOOTSTRAP_API_KEY`.

**For a new deployment**, create a Philter user for Arbiter and a key with only
the `redact` and `policies:read` scopes:

```sh
docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users" \
  -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
  --data '{"username":"arbiter","role":"user"}'
docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users/arbiter/api-keys" \
  -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
  --data '{"scopes":["redact","policies:read"]}'
```

The second response holds the key in `apiKey`, shown only once. Set it as
`ARBITER_PHILTER_API_KEY`. Create Arbiter's
policies with a key of this user, or as an administrator with `owner=arbiter`
(which needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter); a key with only
`policies:read` cannot create them.

**If Arbiter already uses an administrator's key**, do not switch to another
user's key: that user would not have the policies and contexts your batches use,
pseudonyms and encrypted values would change, and context mappings cannot be
moved to another user. Instead, keep the user and remove its administrator role:

1. Create another Philter administrator for people, if there is none. With
   [sign-in through Philter](security.md#sign-in-through-philter), do this on the
   Users page. Otherwise, with the current key, create the administrator and a key
   for them holding every scope (Philter only lets a key change a key whose scopes
   it also holds):

    ```sh
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
      --data '{"username":"ops","role":"admin"}'
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s "http://philter:8080/api/api-keys/scopes" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY"
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users/ops/api-keys" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
      --data '{"scopes":[ every scope name from the previous response ]}'
    ```

2. With the new administrator's key (`OPS_KEY`), or their session on the Users
   page, make the user that owns Arbiter's key a `user`. For the bootstrap key that
   user is `admin`:

    ```sh
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X PUT "http://philter:8080/api/users/admin/role" \
      -H "Authorization: Bearer $OPS_KEY" -H "Content-Type: application/json" --data '{"role":"user"}'
    ```

3. Narrow Arbiter's key to `redact` and `policies:read`, finding its `id` in the
   user's key list:

    ```sh
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s "http://philter:8080/api/users/admin/api-keys" \
      -H "Authorization: Bearer $OPS_KEY"
    docker run --rm --network arbiter_arbiter curlimages/curl:8.10.1 -s -X PUT "http://philter:8080/api/api-keys/<id>/scopes" \
      -H "Authorization: Bearer $OPS_KEY" -H "Content-Type: application/json" \
      --data '{"scopes":["redact","policies:read"]}'
    ```

4. Click **Test** under **Admin → Philter**. The warning clears, and the key,
   policies, contexts, and output are unchanged.

## 2. Create PII redaction policies

A **policy** is a JSON document Philter uses to identify and redact PII. It
declares which PII types to look for and what to do with each
(`REDACT`, `MASK`, `REPLACE`, etc.). For the policy schema and examples, see
the [Philter documentation](https://philterd.github.io/philter/).

Policies live in Philter, and belong to the user who owns
`ARBITER_PHILTER_API_KEY`. Create and change them in Philter, for example with
the Policy Editor. Arbiter lists them read-only through the Philter API, with
that key:

1. Click **Policies** in the left-side menu.
2. The table lists the policies in Philter.
3. **View** opens a policy's JSON in a popup.

A batch with no policy selected uses Philter's default policy.

Arbiter talks to Philter through the
[Philter SDK for Java](https://github.com/philterd/philter-sdk-java). Arbiter
reviews plain-text documents. PDF review through Philter is not yet available,
so a PDF upload fails to ingest.

## 3. Create a batch

A **batch** is the unit of organisation in Arbiter. Every document belongs
to exactly one batch. A batch carries:

| Field                | Purpose                                                              |
| -------------------- | -------------------------------------------------------------------- |
| Name                 | Human-readable label (unique).                                       |
| Group                | Which user group can see and act on this batch (visibility scope).   |
| Policy               | Which Philter policy to apply, or Philter's default policy.          |
| PII Threshold        | Per-span confidence floor for auto-accepting detections.             |
| Document Threshold   | Risk-score ceiling for auto-approving whole documents.               |
| PII type weights     | Per-type sensitivity used in the risk score.                         |
| Closed flag          | A closed batch refuses new documents.                                |

Only administrators can create, modify, or close a batch. To create one:

1. Sign in as an administrator and open **Batches**.
2. Click **Create Batch** to open the create dialog and fill it out:
    - **Name** and **Group** are required.
    - **Policy** lists the policies in Philter. **Default policy** uses
      Philter's default policy.
    - **PII Threshold** (default `0.80`) — spans whose confidence is at or
      above this value are auto-accepted. Lower values give the redactor
      more autonomy; higher values send more spans to the reviewer.
    - **Document Threshold** (default `0.25`) — documents whose risk score
      is at or below this value are auto-approved without human review.
3. Click **Create** in the dialog footer.

After creation, anyone with access to the batch can adjust the group,
thresholds, and weights. The policy can be changed with the **Change…** link
in the **Policy** column. Only admins can close a batch.

For more on batch settings see the
[Batches admin guide](admin/batches.md).

## 4. Upload documents

Documents enter Arbiter through one of two paths.

### Through the web UI

1. Click **Upload** in the left-side menu.
2. Choose a **Batch** from the dropdown — only open batches in groups you
   belong to are listed. The "Create a new batch." link below the dropdown
   takes you to the Batches page if you need a new one.
3. Choose a file with **Document**. Plain text (`.txt`) is supported. PDF
   review through Philter is not yet available, so a `.pdf` fails to ingest.
4. Click **Redact Document**.

Arbiter sends the file to Philter using the batch's
policy, persists the document, creates a span for each detection, and
computes the document's risk score. You then land on a preview page where
you can download the redacted text or PDF.

### Through the REST API

Programmatic ingestion uses `POST /api/v1/ingest` with a JSON body
containing the batch ID, a filename, and the text. Authentication is by a
Philter API key holding `users:read`. See the
[REST API reference](reference/api.md).

The API path is admin-or-group-member-only; it returns HTTP 409 when the
batch is closed.

### What happens after upload

Ingestion is **asynchronous**. The upload (or `POST /api/v1/ingest`) persists
the document in `PENDING` and returns immediately; a background worker drains
the redaction queue oldest-first. Multiple Arbiter replicas can run side by
side: each document is claimed atomically (`PENDING → PROCESSING`) so it is
never processed twice. Admins watch the queue at **Admin → Ingest Queue**.

When the worker picks up a document, for each one:

1. Spans are extracted by Philter, scored by confidence.
2. Each span's initial status is set from the batch's PII Threshold:
    - `confidence ≥ threshold` → `APPROVED` (auto-accepted).
    - `confidence < threshold` → `PENDING` (needs review).
3. The document's risk score is computed using the batch's PII weights and
   the count of unresolved spans.
4. The document's status is set:
    - Any `PENDING` span → `REVIEW_REQUIRED`.
    - Otherwise the worker rolls the batch's **Audit Sampling Rate**:
        - sampled in → `AUDIT_REQUIRED` (sent to review for spot-check).
        - not sampled → `AUTO_APPROVED`.
5. The document is indexed in OpenSearch (full original text + metadata) and
   appears in the queue.

A document whose risk score is at or below the batch's Document Threshold
and that isn't in `AUDIT_REQUIRED` is shown with the `AUTO_APPROVED` label
even before human review; re-tuning the threshold relabels existing rows on
the next page load.

If Philter fails for any reason the document moves to `FAILED` and the
exception is captured both in the Arbiter logs and in a `failureMessage`
field on the document — admins can view the failure detail from the
**View log** link in the Recent failures table on Ingest Queue.

For more detail see [Adding documents](user-guide/uploading.md).

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

1. **Admin** stands up Arbiter, its database, and Philter, with
   `ARBITER_PHILTER_URL` and `ARBITER_PHILTER_API_KEY` set.
2. **Admin** opens **Admin → Philter** and clicks **Test** to confirm Arbiter
   reaches Philter with its key.
3. **Admin** creates a policy tuned to their data in Philter, and checks it is
   listed under **Policies**.
4. **Admin** creates a user group under **Admin → Groups** and adds the
   relevant reviewers to it.
5. **Admin** opens **Batches**, creates `Q3-onboarding-forms`, picks the
   group, picks a policy, and accepts the
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
- [Adding documents](user-guide/uploading.md), [Queue](user-guide/queue.md),
  [Reviewing a document](user-guide/reviewing.md) — page-by-page reference
  for reviewers.
- [Batches](admin/batches.md), [Users and groups](admin/users-and-groups.md),
  [Audit log](admin/audit-log.md) — admin reference.
- [Risk score](reference/risk-score.md), [PII types](reference/pii-types.md),
  [REST API](reference/api.md) — reference material.
