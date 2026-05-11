# Tools

The **Tools** tab in Admin (`/admin/tools`) holds destructive maintenance
actions that don't belong on the data-source, destination, or general
settings pages. Each tool is gated to the `ADMIN` role; users with only the
`AUDITOR` role won't see the Tools tab at all.

Today the page hosts a single tool:

## Clean up data import jobs

Removes finished data-import job rows along with their per-file log entries
from the database. This is useful after a large or one-off import has
created a long history that you no longer want listed on the Background
Jobs page.

### What is removed

- Every row on the **Data Import Jobs** table whose status is
  **COMPLETED** or **FAILED**, across all import types
  (OpenSearch, Elasticsearch, local directory, S3-compatible).
- The per-file log entries attached to those jobs — the same rows the
  **Log** button on the Background Jobs page would display.

### What is left in place

- Jobs whose status is **PENDING** or **RUNNING**. An in-flight or queued
  import is never deleted out from under the worker, so the tool is safe
  to run even while imports are happening.
- Log entries attached to those still-in-flight jobs.
- Documents that were already ingested by prior imports. The tool only
  cleans up bookkeeping rows; it does not touch the document store, the
  document queue, batches, or anything in OpenSearch/Elasticsearch.

### Running the tool

1. Navigate to **Admin → Tools**.
2. Click **Clean up data import jobs**.
3. A confirmation dialog summarises what will happen. Click **Clean up** to
   proceed, or **Cancel** to back out.
4. On confirm, a green banner reports the number of job rows and log
   entries that were removed. Pending and running jobs are listed as
   "left in place" so you have explicit confirmation they were spared.

The action is recorded in the [audit log](audit-log.md) as
`ADMIN_TOOLS_CLEANUP_DATA_IMPORTS` with the deletion counts and the set of
statuses that were eligible (currently `COMPLETED` and `FAILED`).
