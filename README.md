# Arbiter

**Arbiter is a human-in-the-loop deidentification platform.** It runs your
documents through the [Philter / Phileas](https://www.philterd.ai) PII
detector and gives reviewers a fast, focused workflow for confirming or
correcting every detection before the document leaves the redactor.

Where most redaction tools either refuse to make a judgment call or make
one silently, Arbiter surfaces the call as a span on a page and asks a
person to ratify it with all the context they need on one
screen, and with an audit trail that holds up to compliance review.

## What you can do with it

### Get documents in

- **Upload** plain-text and searchable PDF documents one at a time, or
  submit them in bulk via the REST API.
- **Pull from where they already live** by registering external
  [data sources](docs/docs/admin/data-sources.md) — OpenSearch and
  Elasticsearch indices today, with Amazon S3, relational databases, and
  local filesystem directories scaffolded for upcoming work. Re-running an
  import skips documents you've already imported (matched by source
  index + document id) so you can rerun safely.
- **Group documents into batches.** A batch carries its own redaction
  thresholds, PII-type weights, audit sampling rate, compliance profile,
  and approval rules. Batches close one-way when you're done with them.
- **Mark documents as Low / Normal / High priority** at submission time
  so reviewers can pull the urgent ones forward.

### Detect, score, and triage

- **Two redaction engines.** Use a remote Philter instance for the
  full-strength model and policy editor, or fall back to the bundled
  Phileas library for self-contained deployments.
- **Risk scores per document** combine span confidence, PII-type
  sensitivity, and a length-aware penalty for unresolved detections —
  configurable per batch via per-PII-type weights.
- **Auto-approval for low-risk documents.** Anything below the batch's
  Document Threshold is approved without human review, with a configurable
  **audit sampling rate** that pulls a fraction of those back for
  spot-checks.
- **Dual-approval rules.** Per-batch rule sets can require two reviewers
  for documents that hit conditions like "contains an SSN," "risk above
  0.9," "reviewer has approved fewer than 100 documents," and others.
  AND-within / OR-across rule sets express almost any policy.

### Review

- **Side-by-side review pane** with original on the left, redacted output
  on the right, and a navigable list of every span — clickable to jump
  between panes.
- **Per-span actions.** Accept, refuse, change the PII type, or hit
  *Redact All Like This* to apply your decision to every other occurrence
  of the same text in the document.
- **Manual spans.** Highlight any text the redactor missed and add it as
  a manually-created span.
- **Document Previous / Next** order is **highest risk first** by default
  (with a stable id tie-break), so the riskiest documents in a batch get
  attention first. Personal settings let reviewers skip already-decided
  documents and auto-advance after Approve / Reject.
- **Focus mode** hides the chrome and leaves only the document, navigator,
  and Approve/Reject controls — great for long review sessions.
- **Compliance profiles with exemption codes.** When a batch is configured
  to require it, accepting a redaction prompts the reviewer to pick a code
  from the profile, recorded with the span for compliance reporting. The
  prompt can be turned off per batch when codes aren't required.
- **Document locks** prevent two reviewers from editing the same document
  at the same time. Locks expire so abandoned sessions don't stall a batch.
- **LLM-as-a-Judge.** Send a span (or a whole document) to a configured
  Ollama instance for a second opinion: the LLM explains the risk in plain
  English or judges whether the span is genuinely PII or a false positive.

### Find and report

- **Full-text search** across every ingested document via OpenSearch.
  Hits in batches you can't see are masked rather than dropped, so you
  always know whether more results exist.
- **More like this** runs a similarity query off any open document.
- **Reports** roll up totals by domain, by reviewer, by Philter
  instance/policy, and by batch + priority — useful for SLA tracking and
  pinpointing policies that are letting too much through.
- **Background Jobs** page tracks long-running data-source imports with
  per-job Successful/Failed/Skipped counts and a Details popup. The user
  who started a job gets an inbox notification when it finishes.

### Multi-user, multi-tenant-ish

- **Users and groups.** Every batch belongs to one group; reviewers see
  only the batches they have access to.
- **Roles.** Reviewers see and act on documents in their groups; admins
  see everything (with an opt-in "Limit to my groups" filter on the queue
  and batch pages so they can review like a regular user).
- **TOTP-based MFA** for any user, optionally enforced site-wide.
- **Per-user Inbox** with an unread-count badge on the sidebar — used
  for system messages including data-import job completions.

### Operate

- **Ingest queue** dashboard with rolling 24-hour intake, throughput, and
  skipped/failed counters. Admins can drop stuck pending documents.
- **Background-job concurrency** is admin-controlled (1–10 jobs in
  flight system-wide) with strict per-batch serialization — multiple
  imports for the same batch queue up and run in order, no race
  conditions.
- **Audit log** records every state-changing action with user, resource,
  timestamp, and context. Filterable in the UI and exportable as JSON or
  CSV; per-document audit history is also one click from the Document
  Queue.
- **AES-GCM at-rest encryption** for every credential admins type
  (Philter API keys, OpenSearch / Elasticsearch / database passwords, S3
  access and secret keys). Plaintext is never displayed back, never
  logged, never returned by the API.
- **Document content integrity.** Every document gets a SHA-512 hash at
  ingest, recorded on the document row for chain-of-custody.
- **Finalization policies.** Per-batch policies govern what happens to
  the original content once a document is finalized, and Arbiter issues
  a tamper-evident **Certificate of Redaction** for finalized documents.
- **Horizontal scale.** Sessions live in Valkey via Spring Session, the
  ingest queue uses atomic Mongo claims, and the data-import lock is
  enforced by a partial unique index — so multiple Arbiter replicas can
  sit behind a load balancer without sticky sessions.

### Talk to it programmatically

- **REST API** under `/api/v1/*` covers ingest, search, comments,
  LLM-judge, finalize/audit, and full span CRUD. Authenticate with a
  per-user Bearer API key.
- **OpenAPI 3** definition is served by the running app and consumable in
  Swagger UI:
  - Swagger UI: <http://localhost:8080/swagger-ui.html>
  - OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- **In-app documentation.** The full user and admin guide is bundled
  inside the running app at <http://localhost:8080/docs/> — same content
  you can browse on disk under [`docs/docs/`](docs/docs/).

## Quick start

The fastest way to try Arbiter is the bundled Docker Compose stack:

```sh
docker compose up --build
```

That brings up Arbiter, MongoDB, OpenSearch, Elasticsearch, Valkey (for
session state), and the redaction policy editor. Once everything is
healthy, open <http://localhost:8080/> and sign in with the seeded admin
account printed to the application log on first start.

For a longer walk-through — installing without Docker, configuring SMTP,
registering Philter instances, creating users and groups — see the
in-app docs at `/docs/` or browse them in the repo at
[`docs/docs/getting-started.md`](docs/docs/getting-started.md).

## Documentation

- **In-app**: <http://localhost:8080/docs/> after starting the app.
- **In the repo**: [`docs/docs/`](docs/docs/) (mkdocs-material). To
  preview locally: `cd docs && pip install -r requirements.txt && mkdocs serve`.
- **For engineers** working on Arbiter itself: see
  [`DEVELOPER.md`](DEVELOPER.md) for the build/test/architecture guide.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
