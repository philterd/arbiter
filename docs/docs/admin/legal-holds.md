# Legal holds

**Admin → Legal Holds** (`/admin/holds`) manages Philter's legal holds through Philter's API as
you. A legal hold blocks the deletion and purge of Philter's redaction ledger evidence until it is
released. It covers either one document's ledger chain or all of a user's ledger evidence. Holds do
not cover contexts, policies, or documents. See Philter's
[Legal Holds](https://philterd.github.io/philter/latest/redaction/legal_holds.html).

Administrators place and release holds; auditors can view them.

## Not the "Keep original documents" finalization option

A batch finalization policy's **Keep original documents (never delete them)** option is Philter
Workspace's own: when a document in the batch is finalized, Philter Workspace keeps its original
text, where the other options delete it. It does not place a Philter legal hold, and a Philter
legal hold does not keep Philter Workspace's documents. (The option was labeled "Leave original documents (legal hold)"
before; its stored value, `LEGAL_HOLD`, is unchanged.)

## The list

The list shows each hold's reference, what it covers (and the document ID or username), its
reason, and when it was placed, newest first. Search by reference, and sort by reference or by
when it was placed. Philter does not report who placed a hold.

**Owner** (administrators only) shows another user's holds, by username, or **Every user's**,
which needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter. Batches are redacted with
`PHILTER_WORKSPACE_PHILTER_API_KEY`, so the ledger evidence they produce belongs to the Philter
user that owns that key; to hold it, enter that username in the **Owner** filter.

## Placing a hold

**Place hold** takes:

- **Reference**: your name for the hold, such as a matter number, unique among the user's holds.
  It cannot contain `/`, `\`, `;`, or `%`, and cannot be `.` or `..`. Philter's audit log records
  it.
- **What it covers**: one document's ledger chain, by the document ID in Philter's ledger, or all
  of the user's ledger evidence.
- **Reason** (optional).

## Releasing a hold

**Release** asks you to confirm. The evidence the hold covered can then be deleted or purged
again, unless another hold still covers it. Only the person who placed a hold, or a Philter
administrator, can release it. Philter refuses a change while it is deleting or changing holds on
the same user's evidence; try again shortly.

Philter records placing and releasing a hold in its audit log (`legal_hold_set` and
`legal_hold_released`, with the reference), readable from **Audit Log → Philter**. Philter
Workspace's own log does not repeat them.
