# Ledgers

**Admin → Ledgers** (`/admin/ledgers`) shows Philter's redaction ledger through Philter's API as you.
Philter records each redaction made in a context with the ledger on (see
[Contexts](../workflow.md#contexts)), one hash-chained, signed chain per document. See Philter's
[Redaction Ledgers](https://philterd.github.io/philter/latest/redaction/ledgers.html).

Administrators can export and delete chains; auditors can view them.

## Chains

The list shows each chain's document ID, filename, and when it was created, newest first. Search by
document ID or filename, and sort by filename or created. A chain whose first entry can no longer be
read is marked.

**Owner** (administrators only) shows another user's chains, by username, or **Every user's**,
which needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter; search does not apply to every
user's chains. Batches are redacted with `PHILTER_WORKSPACE_PHILTER_API_KEY`, so their chains belong
to the Philter user that owns that key, and only when the batch's context has the ledger on.

## A chain

**View** opens a chain. Philter checks the whole chain each time it opens:

- **Hash chain**: every entry matches its hash and links to the entry before it, which an entry
  changed, removed, or reordered after it was written would break. When it fails, Philter's log
  names the first such entry and its position.
- **Signatures**: Philter signs every entry it writes, so every entry must carry a signature that
  verifies against Philter's signing keys. An entry without one fails.

A chain verifies only when both checks pass. When an entry can no longer be read, Philter cannot
check the chain and returns none of its entries.

The entries are listed in chain order, 25 to a page, with each one's type, replacement, position,
policy and version, when it was recorded, and the start of its hash. The first entry is the start of
the chain. Original values are not shown.

## Export

**Export (JSON)** (administrators only) downloads the chain as Philter exports it: every entry with
its original value and replacement, and the keys that check the signatures, so the chain can be
verified offline. The original values are personal information, so keep the file as you would the
documents themselves. Philter Workspace does not keep a copy. Philter refuses to export a chain with
an entry it cannot read.

## Delete

**Delete** (administrators only) asks you to confirm, then deletes a document's whole chain; Philter
has no way to delete a single entry. Philter allows it only for a Philter administrator, only with
`LEDGER_DELETION_ENABLED=true`, only for a completed chain (not one still being written or needing
recovery), and not while a [legal hold](legal-holds.md) covers the chain or another deletion or hold
change for the same user is running. The page says which applies, except that Philter does not say
which of the last two refused it.

Exports and deletions are recorded in Philter Workspace's audit log as `LEDGER_EXPORT` and
`LEDGER_DELETE`, with the document ID and whose chain it is.
