# Risk score

A document's **risk score** is a number in `[0.0, 1.0]` that summarizes how
much PII the redactor found and how unresolved it is. It drives the queue's
sort order and the **`AUTO_APPROVED`** label.

## Formula

$$
R = \min\!\left(1.0,\ \frac{\sum_{i=1}^{n} S_i \cdot (1 - C_i)\ +\ \mathrm{Penalty}(A)}{N_{\text{words}}}\right)
$$

Where:

- $S_i$ — the **sensitivity weight** of span *i*'s PII type, taken from the
  batch's per-type weights map (see [PII types](pii-types.md) for defaults).
- $C_i$ — span *i*'s **confidence** from the redactor, clamped to
  `[0.0, 1.0]`.
- $A$ — the count of **unresolved** spans in the document — currently those
  with status `PENDING` (i.e., neither auto-approved nor manually decided).
- $\mathrm{Penalty}(A) = A$ — flat one risk-unit per unresolved span.
- $N_{\text{words}}$ — the document's word count, computed by splitting the
  original text on whitespace.

If $N_{\text{words}} = 0$ or there are no spans, $R = 0$. The outer
$\min(1.0, \cdot)$ clamps the result so a tiny document with very
high-weight PII can't blow past 1.

## Why `(1 - C_i)`?

Higher confidence detections add **less** risk per span. The intuition is
that a span the redactor is very confident about doesn't need much human
attention — the risk it carries (per character of text) is mostly resolved
already. Low-confidence detections add more risk because a human still has
to confirm them.

## When it's recomputed

The score is computed at upload time
([web upload](../user-guide/uploading.md), API ingest) and stored on the
document. It is **not** automatically recomputed when the batch's weights
change, when the threshold changes, or when reviewers flip span statuses —
the persisted score reflects the state at upload. Adjusting batch weights
only affects new uploads.

## Worked example

Suppose a batch uses default weights and the document has 200 words and four
spans:

| Span | Type           | $S_i$ | $C_i$ | $S_i (1 - C_i)$ | Status     |
| ---- | -------------- | ----: | ----: | --------------: | ---------- |
| 1    | `ssn`          |    10 |  0.95 |            0.50 | `APPROVED` |
| 2    | `phone-number` |     5 |  0.60 |            2.00 | `PENDING`  |
| 3    | `person`       |     3 |  0.85 |            0.45 | `APPROVED` |
| 4    | `zip-code`     |     2 |  0.40 |            1.20 | `PENDING`  |

Sum of $S_i(1 - C_i) = 0.50 + 2.00 + 0.45 + 1.20 = 4.15$.

$A = 2$ (spans 2 and 4 are `PENDING`), so $\mathrm{Penalty}(A) = 2$.

$R = \min(1.0,\ (4.15 + 2) / 200) = \min(1.0,\ 0.03075) = 0.0308$.

Below the default Document Threshold of `0.25`, so this document is shown as
**`AUTO_APPROVED`** in the queue.

## What about Anchor Words?

The original formula uses "unresolved Anchor Words" for $A$. Arbiter doesn't
track anchor words separately, so today $A$ is the count of spans with
status `PENDING` — the closest available proxy. If the redactor exposes
anchor-word information in a future version, $A$ can swap to that without
changing the rest of the formula.
