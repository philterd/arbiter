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

The header has Approve / Reject / Unapprove buttons. These set the *document's*
status:

- **Approve** — moves the document to `APPROVED` and returns you to the queue.
- **Reject** — moves the document to `REJECTED` and returns you to the queue.
- **Unapprove** — only shown for already-approved documents; flips the document
  back to `REVIEW_REQUIRED` so it can be re-reviewed.

Approve / Reject are hidden once the document is in a terminal status
(`APPROVED`, `REJECTED`, or `FAILED`). The decision is captured in the audit
log along with who made it and when.

## What happens to the underlying spans

- Span status changes (Accept / Refuse) are persisted on every click.
- Type changes are persisted on every selection change.
- Document Approve / Reject does not modify span statuses — it only marks the
  document. Approved documents typically have all their spans accepted by the
  time you click Approve.
