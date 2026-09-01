# Full text search

Full text search is the optional capability that lets reviewers find documents
by their content. When enabled, every document Arbiter ingests is also pushed
into an **OpenSearch** cluster, and the **Search** page in the sidebar plus
the [`GET /api/v1/search`](../reference/api.md) endpoint serve queries from
that index.

The feature is **enabled by default** but the connection details to the
OpenSearch cluster live entirely in the database — there is no environment
variable to set on first startup.

The settings panel lives under **Admin → Settings** on the General tab,
inside the **Full text search** card.

## What full text search is used for

- The **Search** page in the sidebar runs a full-text match over every
  document the caller is allowed to see. Results outside the caller's group
  visibility return as `restricted: true` with content fields nulled, so the
  caller knows a hit exists without seeing what it is.
- The `GET /api/v1/search` REST endpoint serves the same query for
  programmatic clients.
- The **Find similar documents** button on the document review page runs
  OpenSearch's `more_like_this` query to surface documents in the same batch
  whose text resembles the one currently open. The button is hidden on the
  review page when full text search is disabled.

What full text search is **not** used for: it does not drive the queue,
status filtering, dual-approval evaluation, audit log, or any of the
review-decision pipeline. Disabling full text search affects discovery only;
ingest, redaction, review, and finalize all continue to function normally.

## Enabling and configuring full text search

The Full text search card carries five fields:

| Field        | Required | Notes                                                                                   |
|--------------|----------|-----------------------------------------------------------------------------------------|
| Enabled      | yes      | Master on/off switch. **Enabled by default** for new and legacy installs.                |
| Endpoint     | yes      | Base URL of the OpenSearch cluster, including scheme and port (e.g. `http://opensearch:9200`). |
| Index name   | yes      | Lower-case letters, digits, `-`, `_`, `+`. Defaults to `arbiter-documents`.              |
| Username     | no       | Optional HTTP basic-auth username for clusters that require it.                         |
| Password     | no       | Optional basic-auth password. Encrypted at rest with AES-GCM (the same scheme as data-source credentials). Leave blank to keep the previously stored value; tick **Clear stored password** to wipe it back to none. |

Click **Save**. Arbiter immediately tries to reach the cluster and one of
four things happens:

1. **The index does not exist** — Arbiter creates it with the canonical
   mapping (one `text` field on `originalText` for full-text matching, plus
   keyword fields on `id`, `batchId`, `filename`, and `status` for filters)
   and the settings are saved. A success banner confirms the new index.
2. **The index exists with the canonical mapping** — settings are saved
   without modifying the index. A success banner confirms the reuse.
3. **The index exists but its mapping differs from canonical** — settings
   are **not** saved yet. A yellow callout appears below the form with a
   side-by-side diff of the existing and expected mappings, and two buttons:
    - **Continue with existing index** — saves the settings as submitted and
      uses the existing index as-is. Search may still work but documents
      that relied on the canonical mapping (e.g. `originalText` indexed for
      full-text matching) may not surface as expected. Recommended only when
      you know exactly why the mapping differs and want to keep it.
    - **Cancel** — discards the submission. The form is reset and nothing
      is saved.
4. **OpenSearch is unreachable, or returns a server error** — settings are
   not saved and an error banner explains why. Fix the connection (or auth)
   and try again. The probe is intentionally a hard gate when the feature
   is being **turned on** — saving an endpoint that isn't reachable would
   only produce a steady stream of failed indexing calls.

The save event is recorded in the [audit log](audit-log.md) as a
`GENERAL_SETTINGS_CHANGE` row carrying the previous and new values
(passwords are never logged, only a `passwordChanged` boolean).

### Choosing an index name

A single OpenSearch cluster can host multiple deployments by giving each
its own index. Pick a name that is descriptive enough to identify the
deployment when looking at the cluster directly — e.g. `arbiter-staging`,
`arbiter-prod-redactions`. The index is created on first save; renaming it
later writes a brand-new empty index, so the search page will be empty
until enough new documents are ingested.

### Mapping comparison rules

Arbiter compares only the canonical fields that the search code reads back
(`id`, `batchId`, `filename`, `status`, `originalText`). Extra fields on the
existing index — added by hand, by another tenant, by a previous Arbiter
version — are **tolerated**. A mapping is reported as a mismatch only when:

- A canonical field is missing entirely from the existing index.
- A canonical field exists but with a different `type` (for example, an
  `originalText` field that's been declared as `keyword` rather than
  `text` cannot serve full-text match queries).

This loose comparison keeps the mismatch dialog rare in practice while still
catching the differences that actually break search.

## Disabling full text search

Untick **Enable** on the same form and click **Save**. Disabling does not
require the cluster to be reachable — Arbiter writes the setting and skips
the OpenSearch probe entirely. This means you can turn the feature off even
if the OpenSearch cluster is currently down.

When disabled:

- New ingests **do not** push the document text to OpenSearch. The text
  still lives in MongoDB exactly as it did before, and review continues to
  work normally.
- The **Search** page returns empty results (an informational note explains
  why). The `GET /api/v1/search` endpoint behaves the same way.
- The **Find similar documents** button on the review page is **not
  rendered** — reviewers won't see it at all rather than getting a button
  that produces empty results.
- The OpenSearch cluster, if you still have one running, is left untouched.
  No documents are deleted; previously-indexed documents stay in the index
  so a future re-enable can use them as-is (subject to the mapping
  comparison above).

### Implications of disabling

- **Search-by-text becomes unavailable.** Reviewers can still find documents
  by batch, by status, by priority, by filename (the queue page filters all
  do this without OpenSearch), and by document ID. They cannot run
  free-text matches over the body of every document.
- **Find similar documents goes away.** This is the only feature that
  surfaces textually-related documents in the same batch.
- **No new documents index.** If you re-enable later, the gap between
  disable and re-enable will not be back-filled — only documents ingested
  after re-enable show up in search results. Run a re-index against the
  cluster yourself if you need full coverage.
- **Reports, audit log, and the review queue are unchanged.** None of
  those depend on OpenSearch.
- **Health stops reporting the cluster.** With the feature enabled, an
  unreachable cluster makes `/api/health` and `/actuator/health` report
  `DOWN`, because search and indexing are degraded. With it disabled, no
  cluster is expected and health ignores OpenSearch entirely. If you do not
  run a cluster, turn the feature off rather than leaving health down. See
  [REST API → Health and metrics](../reference/api.md#health-and-metrics).

Disabling is the right choice when:

- You don't have an OpenSearch cluster available and don't want indexing
  attempts to flap against `localhost:9200`.
- Your deployment policy disallows indexing PII-bearing document text in a
  second store. Note that with full text search **enabled**, the
  unredacted source text is sent to OpenSearch in plaintext (see
  [Security considerations](#security-considerations) below). If you
  cannot satisfy the precautions listed there, leave the feature off.
- You're temporarily isolating the deployment from the OpenSearch cluster
  for maintenance and want clean signals (no warning logs about indexing
  failures).

## Security considerations

!!! danger "PII is indexed in OpenSearch when this feature is enabled"
    Enabling full text search causes the **complete, unredacted source text**
    of every ingested document to be written to the configured OpenSearch
    cluster — including any PII that document contains (names, email
    addresses, SSNs, phone numbers, account numbers, medical details, …).
    This is the design: OpenSearch needs the raw text to power the
    full-text match query.

    The OpenSearch index sits **outside** the at-rest encryption boundary
    that covers MongoDB. The
    [`SymmetricCipher` field-encryption callbacks](../security.md#pii-at-rest-in-mongodb)
    encrypt PII inside MongoDB before it is written to disk; **no equivalent
    transformation runs on the OpenSearch indexing path.** Documents go
    over the wire to OpenSearch as plaintext and are stored as plaintext.

    Treat the OpenSearch cluster, the network path to it, the basic-auth
    credentials configured below, the disks and snapshots backing it, and
    any backups taken from it as **part of the same security boundary as
    your MongoDB instance**. Anything less and the search index becomes the
    weakest point in your PII handling.

Concretely, the precautions you need to apply to the OpenSearch cluster
when full text search is enabled:

- **Encrypt the OpenSearch data directory at rest.** Use disk-level
  encryption (LUKS, dm-crypt, EBS encryption, Azure Disk Encryption, GCP
  CMEK) on every node in the cluster. The same volume and the same key
  management posture you give your MongoDB data directory.
- **Encrypt OpenSearch backups and snapshots.** Snapshots written to S3
  or another object store carry the full document text. Encrypt the
  destination bucket and rotate keys on the same schedule as your other
  PII backups.
- **Encrypt the network path.** Use **HTTPS** for the endpoint
  (`https://` in the field above) so the document text isn't exposed on
  the wire between Arbiter and OpenSearch. A `http://` endpoint is fine
  only if Arbiter and OpenSearch are co-located on a trusted network
  segment that isn't shared with anything else — a single Docker bridge
  network on the same host, for example.
- **Require authentication.** Configure OpenSearch's security plugin (or
  your reverse proxy) to require basic auth, and enter the username and
  password into this form. Arbiter encrypts the password at rest with the
  same AES-GCM scheme used for data-source credentials. **Anonymous
  access to the cluster equals anonymous access to every PII document
  Arbiter has ever ingested.**
- **Scope the credential to least privilege.** The user Arbiter logs in
  as needs only:
    - `indices:data/write/index` and `indices:admin/create` /
      `indices:admin/get` on the configured index name (so Arbiter can
      create the index on first save and write documents on every ingest).
    - `indices:data/read/search` on the same index (so the Search page
      can run match and `more_like_this` queries).
    Avoid cluster-wide admin or all-indices grants.
- **Restrict network reachability.** Bind OpenSearch to a private network
  interface; don't expose `9200` on a public IP. Lock the firewall down
  to the subnets that Arbiter runs on.
- **Audit OpenSearch access independently.** Arbiter writes
  `DOCUMENT_PII_SENT_TO_LLM` rows when text leaves for an LLM, but it
  does **not** write a per-document audit row for every OpenSearch index
  operation (those happen on every ingest and would flood the audit log).
  Rely on OpenSearch's own audit logging or your network capture tooling
  to attribute reads of the index.
- **Apply the same retention policy as MongoDB.** When a batch is
  finalized and a finalization policy purges the source text from
  MongoDB, the OpenSearch document is **not** automatically deleted.
  Either disable full text search for batches under a delete-immediately
  policy, or run a periodic reindex/cleanup job against OpenSearch that
  honors the same retention rules.

If your deployment policy does not allow PII to live in a second store
even with all of the above applied, **disable full text search** (see
[Disabling full text search](#disabling-full-text-search) above). With
the feature off, the document text never leaves MongoDB.

See [Security · PII at rest in MongoDB](../security.md#pii-at-rest-in-mongodb)
for the broader at-rest story; the section there explicitly calls out
OpenSearch as needing its own encryption.

## Troubleshooting

- **"Could not reach OpenSearch at … : Connection refused"** — the cluster
  is down, or the endpoint URL is wrong. Double-check the scheme and port,
  and try again.
- **"OpenSearch returned HTTP 401"** — the cluster requires authentication
  but the credentials supplied are missing or wrong. Re-enter the username
  and password and re-save.
- **Mapping mismatch dialog every time** — something is creating the index
  with a non-canonical mapping (a templated index, an external indexer, or
  manual `curl` setup). Either delete the index out-of-band so Arbiter can
  recreate it, or click **Continue with existing index** and accept the
  divergence.
- **Health reports `DOWN` and every other dependency looks fine** — full
  text search is enabled but the cluster is not answering. Fix the endpoint
  or the credentials, or untick **Enable** if this deployment has no
  cluster.
- **Search page is empty even though documents exist** — the feature was
  disabled at some point during ingestion. Re-enable, then either re-index
  against the cluster yourself or accept that only post-re-enable documents
  will appear.
