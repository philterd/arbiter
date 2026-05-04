# Queue

The **Queue** (the page at `/`, also linked from the sidebar) is your starting
point as a reviewer. It lists every document you have access to, ordered by
risk score (highest risk first), so you can work top-down.

## Columns

| Column     | Notes                                                                       |
| ---------- | --------------------------------------------------------------------------- |
| Filename   | Original filename of the uploaded document                                  |
| Batch      | The batch the document belongs to                                           |
| Status     | Document status (see below)                                                 |
| Risk Score | A number between 0.00 and 1.00 — see [Risk score](../reference/risk-score.md) |
| Actions    | A **Review** link that opens the side-by-side review pane                   |

The status pill colors are:

- Green — `APPROVED`, `AUTO_APPROVED`
- Yellow — `REVIEW_REQUIRED`
- Blue — `PENDING`
- Red — `REJECTED`, `FAILED`

`AUTO_APPROVED` is shown in place of the underlying status for any document
whose risk score is at or below its batch's Document Threshold and that hasn't
been explicitly approved or rejected.

## Filters

- **Batch** — narrow the list to a single batch.
- **Status** — narrow the list to a single status value (the `AUTO_APPROVED`
  label is presentational; filter by the underlying status to see those rows).
- **Clear** — wipes both filters.
- **Refresh** — re-fetches without changing filters or pagination.

Each filter change resets pagination to page 1.

## Limit to my groups (admins only)

Admins see a checkbox labeled **Limit to my groups**. With it checked (the
default), an admin sees only the batches and documents in groups they belong
to — exactly what a reviewer in those groups would see. Unchecking it expands
the view to every batch and document in the system. Toggling reloads the
batch dropdown to match the new scope.

The checkbox state isn't persisted across pages — it resets to "checked" each
time you load the queue.

## Pagination

The queue is paginated 10 documents per page. Page navigation uses the
**Previous** and **Next** buttons; the position indicator shows
`Page N of M (X documents)`.
