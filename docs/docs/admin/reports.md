# Reports

The Reports page lives at `/reporting` and is open to admins and auditors.
It is a read-only summary of activity over a chosen date range across every
batch the caller can see.

This page documents the reports that depend on
[Blind Double Review](batches.md#blind-double-review). The other charts and
tables on the page (status counts, span edit rates, per-domain aggregates,
per-reviewer activity) are self-describing and not duplicated here.

## Inter-Annotator Agreement (IAA)

The **Inter-Annotator Agreement (IAA)** card sits at the bottom of the
Reports page. It is rendered for every batch that has
[Blind Double Review](batches.md#blind-double-review) enabled. Batches that
do not have the feature turned on are not listed.

For each such batch the report shows:

| Column        | Meaning                                                                              |
|---------------|--------------------------------------------------------------------------------------|
| Batch         | Batch name.                                                                          |
| Documents     | Number of documents in the batch that have completed both first and second reviews. |
| Tokens        | Total tokens compared across those documents.                                        |
| Cohen's Kappa | Pooled token-level Cohen's Kappa between the two reviewers (see method below).      |

If a batch has no doubly-reviewed documents yet, the **Cohen's Kappa**
column reads *— not enough data —*. As soon as at least one document has
been reviewed by both reviewers, a numeric kappa is computed.

### How the score is computed

The score is **token-level Cohen's Kappa**:

1. For every double-reviewed document with snapshots from both reviewers,
   the original document text is split on whitespace into tokens
   (one token = one whitespace-delimited word).
2. Each token is given a binary label per reviewer:
    - **PII** — the token's character range overlaps any APPROVED span the
      reviewer left at the moment they completed their review.
    - **O** — otherwise.

    Partial overlap counts as PII. This is conservative: a token that touches
    a PII span at all is treated as PII for the agreement calculation.
3. The per-token decisions are pooled across **every** double-reviewed
   document in the batch into a single 2x2 confusion matrix
   (both PII / first-only / second-only / both O).
4. Cohen's Kappa is computed once per batch from that pooled matrix.

Pooling at the batch level (rather than averaging per-document kappas)
gives a single defensible score even when individual documents are short
or lopsided in their PII vs. O distribution.

### How to read the score

The score is colored:

| Score range  | Color  | Reading                                                                                              |
|--------------|--------|------------------------------------------------------------------------------------------------------|
| **≥ 0.80**   | Green  | Substantial-to-near-perfect agreement. Reviewers are calibrated; the redaction policy is being applied consistently. |
| **0.60–0.79**| Amber  | Moderate agreement. Worth spot-checking the disagreements; usually points at edge cases in the policy. |
| **< 0.60**   | Red    | Low agreement. Indicates a real training gap or an ambiguous rule in the redaction policy that needs to be tightened. |

A kappa of **0.9** means the Blind Double Review process is acting as a
sanity check on a healthy pipeline; the admin can sleep soundly. A kappa of
**0.5** is a signal that the process has surfaced a meaningful issue —
either reviewers need additional guidance, or the redaction policy itself
has ambiguous cases the team needs to resolve.

### Edge cases

- **Both reviewers labeled every token as O.** Cohen's Kappa is degenerate
  in this case (the standard formula evaluates to 0/0). Arbiter reports
  **1.000** by convention, reflecting unanimous agreement.
- **Only one reviewer reviewed.** The document is excluded from the
  pooled matrix until the second reviewer completes their review.
- **Document text is empty.** The document is excluded.
- **A reviewer left no APPROVED spans.** Their labels are all O for that
  document, which is valid input to the kappa calculation.
