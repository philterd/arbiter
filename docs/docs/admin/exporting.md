# Exporting annotations

Arbiter can export a batch's reviewed annotations to a [destination](destinations.md)
as labeled data for downstream pipelines. An export runs as a
[background job](../user-guide/background-jobs.md): it reads the batch's
**APPROVED** documents page by page (so a large batch is never loaded into memory
at once) and writes the chosen data format to the destination.

Start an export from the **Batches** page: click **Export** on a batch row, then
choose a destination and a data format. Exporting a batch requires the team lead
for that batch or an administrator.

## What is exported

Only the human-confirmed annotations are exported: a document's **APPROVED** spans.
Rejected spans and spans still awaiting a second opinion (`NEEDS_SECOND_OPINION`)
are excluded. A reviewed document that ended up with no approved spans is still
exported (as a negative example) so the data reflects true negatives, not just
positives.

## Data formats

- **JSONL**: one JSON object per document in a single file, with an `entities`
  array and document `metadata`.
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
