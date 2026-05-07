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
| Started       | When the worker thread started running the job                                                                  |
| Finished      | When the worker thread exited                                                                                   |
| Started by    | The user (or API key owner) who triggered the job                                                               |

## Status flow

A job moves through these states:

1. **`PENDING`** — Created, waiting for the worker thread to pick it up.
   Usually only visible for a fraction of a second.
2. **`RUNNING`** — The worker is paging through the source's results,
   pulling at most **100 hits per page** via the cluster's scroll API,
   pushing each hit's text-field value into the redaction queue. The
   *Progress* column updates after every hit.
3. **`COMPLETED`** — All pages were drained. The Progress column shows the
   final counts.
4. **`FAILED`** — Something stopped the job. The top-level error is shown
   on hover of the FAILED badge and as a red line under the Progress cell.

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
Documents that already imported on the previous attempt remain in the
redaction queue and are not duplicated unless the source query returns the
same hits again.

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
