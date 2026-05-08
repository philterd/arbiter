# Background Jobs

The **Background Jobs** page (`/jobs`) tracks long-running ingest jobs —
right now, the OpenSearch and Elasticsearch ingest paths started from the
[Add Documents](uploading.md) page. It's reachable from the
**Background Jobs** link in the sidebar's **Redaction** group.

The page **auto-refreshes every 10 seconds** so you can leave it open while
a job runs. Each job is one row in the table; the most recent jobs are
listed first.

## What kicks off a job

A row appears here whenever someone clicks **Ingest from OpenSearch** or
**Ingest from Elasticsearch** on the Add Documents page. Other ways of
adding documents (the *Upload files* tab, the REST API, or admin sample-data
loaders) don't go through this page — they enqueue documents directly.

## Columns

| Column        | What it shows                                                                                                  |
| ------------- | -------------------------------------------------------------------------------------------------------------- |
| Type          | `OpenSearch Ingest` or `Elasticsearch Ingest`                                                                   |
| Source        | The data source the job is pulling from (its display name on the [Data Sources](../admin/data-sources.md) page) |
| Batch         | The batch the imported documents are landing in                                                                 |
| Status        | One of `PENDING` / `RUNNING` / `COMPLETED` / `FAILED`. Failed jobs surface the top-level error on hover.        |
| Progress      | `processed of total` when the search backend reported a total, or `N processed` when it did not                 |
| Details       | A blue **Details** link that opens a popup with the job's Type, Source, Batch, Started/Finished timestamps, Started by, and the per-outcome counts (Successful, Failed, Skipped). |

## Status flow

A job moves through these states:

1. **`PENDING`** — Created and waiting in the queue. A background dispatcher
   polls every couple of seconds and atomically promotes one PENDING job per
   batch to `RUNNING` (see [Per-batch queueing](#per-batch-queueing) below).
2. **`RUNNING`** — The worker is paging through the source's results,
   pulling at most **100 hits per page** via the cluster's scroll API,
   pushing each hit's text-field value into the redaction queue. The
   *Progress* column updates after every hit.
3. **`COMPLETED`** — All pages were drained. The Progress column shows the
   final counts.
4. **`FAILED`** — Something stopped the job. The top-level error is shown
   on hover of the FAILED badge and as a red line under the Progress cell.

When a job moves to `COMPLETED` or `FAILED`, the user who started it
receives a one-line **inbox notification** summarising the outcome (source,
batch, Successful / Failed / Skipped counts, and any error message). The
notification appears on the **Inbox** page and is reflected in the
unread-count badge on the sidebar.

## Per-batch queueing

Two rules govern when a `PENDING` job advances to `RUNNING`:

1. **One running job per batch.** Multiple imports can be queued for the
   same batch — they execute in oldest-first order, each waiting for the
   prior to finish. This rule is enforced atomically by a partial unique
   index on the `background_jobs` collection so it holds even when several
   replicas of Arbiter run in parallel.
2. **A global concurrency ceiling.** Across the whole deployment, the
   admin caps how many data-import jobs may be `RUNNING` at any one time
   under **Admin → General → Max concurrent data imports**. Default is `1`;
   the dropdown allows `1`–`10`. Jobs over the cap stay `PENDING` until a
   slot frees up.

Together these rules let admins safely click *Ingest from OpenSearch*
several times against the same batch without worrying about race
conditions; the second click adds another row in `PENDING` that runs after
the first finishes.

## Skipped (already-imported) documents

When an OpenSearch / Elasticsearch ingest is run a second time over the
same source, hits whose `(_index, _id)` pair already exists in MongoDB are
**skipped**. A placeholder Document row with status `SKIPPED` is written
so the import attempt is auditable, but no new content is enqueued and the
existing document keeps its current review state. Skipped hits show up in:

- The **Skipped** counter on the Details popup of the relevant job.
- The **Skipped** widget on the Ingest Queue page (cumulative across all
  data-import jobs).
- The completion notification dropped into the user's inbox.

## Failure details

When a job records `processedDocuments` lower than `totalDocuments`, some
hits failed individually (most commonly because the configured **Text
field** was missing on the hit's `_source`, or the search server returned
an error for that document). The Progress column shows the failure count
and a **Show failure details** disclosure that lists per-hit reasons —
most recent first, capped at 50 entries to keep the row readable. Anything
beyond the cap is in the application log instead.

## Visibility

- **Admins** see every background job in the system, regardless of which
  batch the job is targeting.
- **Reviewers** (non-admins) see only the jobs whose batch belongs to a
  group they are a member of. Jobs targeting batches outside your group
  don't appear in your table.

## Cancelling or retrying

There is no Cancel button — once a job is `RUNNING`, it runs to completion.
A FAILED or partial COMPLETED job can be re-run by clicking the *Ingest
from …* button again on the Add Documents page; this creates a new row.
Documents that already imported on the previous attempt are detected by
their source `(index, id)` pair and recorded as **Skipped** (see above) —
not duplicated.

## Where the imported documents go

Documents pulled in by an ingest job land on the **Document Queue** with
status `PENDING` and follow the regular redaction flow (Philter detects
spans, the queue worker promotes documents to `REVIEW_REQUIRED` /
`AUDIT_REQUIRED` / `AUTO_APPROVED` based on the batch's thresholds — see
[Adding documents](uploading.md#what-happens-behind-the-scenes)). Each
imported document is also stamped with **traceability** back to its
source, viewable on the Review page's **Document Information** popup:

- The system that produced it (`OPENSEARCH` or `ELASTICSEARCH`)
- The cluster URL
- The index the hit lived in (preferred from the hit's own `_index`,
  falling back to the configured query path)
- The OpenSearch / Elasticsearch `_id`
- The **Import Timestamp** when Arbiter pulled it in
