# Reviewing a document

The review page (`/review/{id}`) is where you confirm or correct the redactor's
findings. It has three panes:

1. **Original** — the source text with each detected span highlighted yellow.
2. **Redacted** — the same text with each *accepted* span replaced by a
   `<<TYPE>>` marker. Refusing a span removes the marker from this pane.
3. **PII Navigator** — a scrollable list, one entry per span, sorted by
   position in the document.

The Original and Redacted panes scroll together. Clicking a highlighted span
in either pane focuses the matching entry in the navigator and scrolls it
into view; clicking a navigator entry does the reverse.

## Per-span actions

Each navigator entry shows:

- A **type pill** that's actually a dropdown — click it to change the
  detected PII type (e.g., flip a span from `ssn` to `phone-number`). The
  change is saved immediately and the highlighted text updates.
- The matched **text**.
- The redactor's **confidence** as a percentage.
- A status pill — **Accepted** or **Refused** — that toggles when clicked.
  Refusing a span strikes it through in the navigator and removes its
  redaction marker from the Redacted pane.
- A full-width **Redact All Like This** button. Clicking it scans the
  document for every other occurrence of the exact text and creates or
  updates spans so that all of them are accepted with the same PII type.
  The page reloads to reflect the new spans. (Match is case-sensitive and
  literal — substrings of longer words can match, so use it on
  unambiguous text like a project name or an email address.)

## Document-level actions

The header has **Previous** / **Next** buttons that step through the
documents in this batch. By default the order is **highest risk score
first** (with a stable tie-break on document id), so working straight
through Next pulls the riskiest document of the batch up first and the
lowest-risk last. You can change the sort to **Document Priority** or
**Document Filename** under your [personal review-page
preferences](settings.md); whether the buttons skip documents that have
already been accepted or rejected (`APPROVED`, `AUTO_APPROVED`, `REJECTED`)
is controlled by the same preferences page.

Next to the buttons is a **Document X of Y** counter. `Y` is the number of
*pending* documents in the batch (everything except `APPROVED`,
`AUTO_APPROVED`, and `REJECTED`); `X` is the position of the document you
are currently viewing within that list, ordered by your chosen review
sort. The counter only appears while the current document is itself
pending — once you accept or reject it, it leaves the pending list and the
counter is hidden.

In batches with
[Blind Double Review](../admin/batches.md#blind-double-review) enabled,
**Previous** and **Next** also skip any document flagged for double review
whose first review was performed by you. This keeps the second review
blind — you are never paged into a document you have already reviewed,
even if it is otherwise eligible for the queue.

It also has Approve / Reject / Unapprove buttons. These set the *document's*
status:

- **Approve** — records your approval and returns you to the queue (or
  jumps to the next document if your "advance to next on approve or
  reject" preference is on). Whether the document moves to `APPROVED`
  immediately depends on the batch's [approval rule sets](../admin/rules.md):
  if dual approval is required and you're the first approver, the document
  stays in `REVIEW_REQUIRED` until a second different reviewer approves.
- **Reject** — moves the document to `REJECTED`. Like Approve, it returns
  to the queue by default and jumps to the next document if your
  advance-to-next preference is on.
- **Unapprove** / **Unreject** — only shown for already-decided documents;
  flips the document back to `REVIEW_REQUIRED` so it can be re-reviewed.

## Focus mode

The header's **Focus** button toggles a distraction-free view of the review
page. When focus mode is on:

- The left navigation sidebar, page footer, and Back-to-queue link are hidden.
- The dual-approval progress banner, lock-conflict banner, and the status /
  risk-score / batch metadata strip below the filename are hidden.
- The terminal-status banners (`APPROVED`, `REJECTED`, `FINALIZED`) and the
  Unapprove / Unreject controls are hidden.
- The filename, the Original / Redacted / PII Navigator panes, the Previous /
  Next buttons with the Document X of Y counter, and the Approve / Reject
  buttons remain visible.

Click **Exit focus** to return to the full layout. The toggle is per-tab and
not persisted.

The same reviewer can never approve a document twice — a duplicate click
shows an inline error. The Document Queue's **Approvals** column tracks
progress (e.g. `1 of 2`, `0 of 1`).

Approve / Reject are hidden once the document is in a terminal status
(`APPROVED`, `REJECTED`, or `FAILED`). The decision is captured in the audit
log along with who made it and when.

## What happens to the underlying spans

- Span status changes (Accept / Refuse) are persisted on every click.
- Type changes are persisted on every selection change.
- Document Approve / Reject does not modify span statuses — it only marks the
  document. Approved documents typically have all their spans accepted by the
  time you click Approve.
