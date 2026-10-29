# Exporting annotations

Philter Workspace can export a batch's reviewed annotations to a [destination](destinations.md)
as labeled data for downstream pipelines. An export runs as a
[background job](../user-guide/background-jobs.md): it reads the batch's
**APPROVED** documents page by page (so a large batch is never loaded into memory
at once) and writes the chosen data format to the destination.

Start an export from the **Batches** page: click **Export** on a batch row, then
choose a destination, a data format, and what to do with
[redactions no person decided](#redactions-no-person-decided). Exporting a batch
requires the team lead for that batch or an administrator.

## What is exported

A document's **APPROVED** spans are exported. Rejected spans are not, and a document
cannot be approved while any span is undecided or awaiting a second opinion. A
reviewed document that ended up with no approved spans is still exported (as a
negative example) so the data reflects true negatives, not just positives.

## Redactions no person decided

A span can be `APPROVED` in two ways:

- **By a person**: a reviewer accepted it, refused it and then reset it, applied
  **Redact All Like This** to it, or added it by hand.
- **By the threshold**: its confidence was at or above the batch's PII Threshold,
  so it was accepted at ingest, and no person changed its status afterwards. The
  reviewer approved the document, not the span. Changing only a span's type does
  not make it decided by a person.

Used as training data, threshold-accepted spans teach a model its own
predictions. The export's **Redactions No Person Decided** option controls them:

- **Leave out documents that have any** (the default): a document with any
  threshold-accepted span is not exported. The whole document is left out, rather
  than just those spans, because dropping a span would label its text as not PII.
  The job's **Skipped** count on the Background Jobs page is the number of
  documents left out.
- **Include them**: every approved document is exported. JSONL marks each entity
  with `decidedBy`, `"person"` or `"threshold"`, so a pipeline can filter them.
  BIO and PhEye have no field for it, so their threshold-accepted spans are
  indistinguishable from decided ones.

The `BATCH_EXPORT` audit entry records the option (`excludeThresholdAccepted`)
and the number of documents left out (`skippedDocuments`).

## Data formats

- **JSONL**: one JSON object per document in a single file, with an `entities`
  array and document `metadata`. Each entity has `start`, `end`, `label`, `text`,
  and `decidedBy` (`"person"` or `"threshold"`, never the person's name).
- **BIO**: token-per-line NER format, one `.bio` file per document.
- **PhEye**: the leaner training shape used by the PhEye PII models (described
  below), one JSON object per document in a single file.

## PhEye training format

The **PhEye** format produces JSONL with the same field names and types as the
PhEye model-training corpora (for example `ph-eye-model-training`'s
`passages.jsonl`), so an export is drop-in usable for training and evaluating the
[PhEye](https://github.com/philterd/ph-eye) PII models.

Each line is one independent JSON object (UTF-8, no wrapping array, newlines inside
text escaped as `\n`):

```jsonl
{"text": "Contact John Smith today.", "spans": [{"start": 8, "end": 18, "label": "name"}]}
{"text": "UNITED STATES SECURITIES AND EXCHANGE COMMISSION", "spans": []}
```

Per document:

- `text` is the document's original text.
- `spans` is an array with one entry per approved span:
    - `start` is the inclusive character offset into `text`.
    - `end` is the exclusive character offset into `text`, so
      `text.substring(start, end)` is the labeled span.
    - `label` is the span's PII type, taken verbatim from the reviewed annotation
      (no remapping is applied).

A document with no approved spans is emitted with `"spans": []`.

The exported file is named `<batch>-pheye-<timestamp>.jsonl` so it is
distinguishable from a plain JSONL export in the same destination.
