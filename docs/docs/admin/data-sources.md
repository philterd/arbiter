# Data sources

Data sources let admins point Arbiter at an external place where documents
already live — an OpenSearch or Elasticsearch index, an S3 bucket, a
relational database, or a local filesystem directory — instead of (or in
addition to) uploading files one at a time. Once registered, a data source
becomes selectable on the **Add Documents** page.

The page is at `/admin/data-sources`, also reachable from the **Data Sources**
link in the sidebar's Administration section. ROLE_ADMIN only.

Data sources are the *input* half of Arbiter's I/O. For where finalized
redacted documents go *out*, see [Destinations](destinations.md).

> **Status:** **OpenSearch**, **Elasticsearch**, and **Local Directory**
> ingest are fully wired up — clicking *Ingest from …* on the Add Documents
> page kicks off a background job that pulls documents into the redaction
> queue. **S3** and **Relational Database** ingest are not implemented yet;
> submitting those forms still returns a *"…is not yet implemented"*
> notice.

## Source types

The page is split into five sections, one per type. Names must be unique
*within* a section (case-insensitive) — so an OpenSearch source and an S3
source can both be named `archive` without conflict.

### OpenSearch

| Field          | Required | Notes                                                                         |
| -------------- | -------- | ----------------------------------------------------------------------------- |
| Name           | yes      | Display name; unique among OpenSearch sources (case-insensitive)              |
| Endpoint       | yes      | Cluster URL, e.g. `http://localhost:9200`                                      |
| Query          | yes      | Query that returns the documents to import — typically the JSON body of an `_search` request, prefixed with the index path (e.g. `contracts/_search { "query": { "match_all": {} } }`). The body's `size` is **always overridden to 100** for paging — see [Paged ingest](#paged-ingest) below. |
| Text field     | yes      | Field name within each hit's `_source` that holds the document text to import (e.g. `body`, `content`). |
| Filename field | no       | Optional. Field within each hit's `_source` whose value is used as the imported document's filename. If blank, the OpenSearch `_id` is used as the filename. |
| Username       | no       | Stored as plaintext on the document row.                                       |
| Password       | no       | Encrypted at rest; see [Credential encryption](#credential-encryption).        |

The listing table shows **Name**, **Endpoint**, **Text field**, and
**Actions**. Auth and the saved query are not surfaced inline; the saved
query is editable through the **Edit** action button on each row.

#### Edit a saved source

Each row has an **Edit** button that opens a popup pre-filled with the
saved values (the **Name** is read-only; everything else can be changed).
The Password field is left blank by default — **leaving it blank keeps the
existing password**, typing a new value replaces it, and the **Clear the
stored password** checkbox wipes it. Saving fires an
`OPENSEARCH_DATASOURCE_UPDATE` audit event with the new values plus a
`passwordChanged` boolean.

#### Test the connection

Two **Test** buttons are available:

- The **Test** button beside the **Add** button on the form sends the
  **current form values** (endpoint, query, optional username/password) to
  Arbiter — handy while you're tuning a new source before saving it.
- A **Test** action button on each saved row sends the stored values
  (Arbiter decrypts the saved password server-side; the cleartext is never
  handed back to the browser).

Either way, Arbiter connects to OpenSearch, runs the query, and shows up to
the **first 10 hits** in a popup along with the reported total. A green
*Success* bar tops the popup when the query worked; otherwise a red error
bar shows the reason. Test does not save anything, so it's safe to use
repeatedly.

### Elasticsearch

Elasticsearch data sources behave **identically to OpenSearch**: same
fields, same scrolling ingest, same Test / Edit affordances. They live in
their own collection, so the same name can be used for one OpenSearch and
one Elasticsearch source without conflict.

| Field          | Required | Notes                                                                         |
| -------------- | -------- | ----------------------------------------------------------------------------- |
| Name           | yes      | Display name; unique among Elasticsearch sources (case-insensitive)           |
| Endpoint       | yes      | Cluster URL, e.g. `http://localhost:9200`                                      |
| Query          | yes      | Same shape as OpenSearch — `<index>/_search { … JSON body … }`. The body's `size` is overridden to 100. |
| Text field     | yes      | Field name within each hit's `_source` that holds the document text.          |
| Filename field | no       | Optional. Field within each hit's `_source` whose value is used as the imported document's filename. If blank, the Elasticsearch `_id` is used. |
| Username       | no       | Stored as plaintext on the document row.                                       |
| Password       | no       | Encrypted at rest; see [Credential encryption](#credential-encryption).        |

### Paged ingest

OpenSearch and Elasticsearch ingest jobs use the **scroll API** so they
can pull arbitrarily large result sets without exhausting memory:

1. The first request opens a scroll context (`?scroll=1m`) with the saved
   query body, **forcing `"size": 100`** so each page is bounded — even if
   the saved body says `"size": 5000`, only 100 hits land per page.
2. The worker walks the page, pulls each hit's text-field value, sets
   traceability fields (see below), and pushes the document onto the
   Arbiter ingest queue.
3. It then asks the server for the next scroll page and keeps going until a
   page returns zero hits, at which point it deletes the scroll context.

Each imported document records traceability back to its source: the
`sourceSystem` (`OPENSEARCH` or `ELASTICSEARCH`), the cluster URL, the
index the hit came from (preferred from each hit's `_index`, falling back
to the configured query path), the OpenSearch / Elasticsearch `_id`, and an
**Import Timestamp** of when Arbiter pulled it. Reviewers see all of this
on the **Document Information** popup on the Review page.

Job progress is visible in real time on the **Background Jobs** page (under
the Redaction sidebar group). Each job tracks status (`PENDING` /
`RUNNING` / `COMPLETED` / `FAILED`), how many documents have been
processed vs. the total, and — when individual hits fail — a list of
per-hit reasons accessible behind a *Show failure details* disclosure.

### Amazon S3

| Field          | Required | Notes                                                                  |
| -------------- | -------- | ---------------------------------------------------------------------- |
| Name           | yes      | Unique among S3 sources                                                 |
| Bucket name    | yes      | The bucket to read from                                                 |
| Bucket key     | yes      | Object-key prefix under which to look (e.g. `archive/2026/`)            |
| Filename glob  | yes      | Filter applied within the prefix, e.g. `*.txt`, `**/*.pdf`              |
| Access key     | no       | Encrypted at rest                                                       |
| Secret key     | no       | Encrypted at rest                                                       |

Access key and secret key are validated as a pair — provide both or neither.
Leaving them blank means the runtime will use whatever ambient AWS credentials
the application process has (environment variables, instance profile, etc.).
Credentials are not shown in the listing table.

### Relational Database

| Field     | Required | Notes                                                                     |
| --------- | -------- | ------------------------------------------------------------------------- |
| Name      | yes      | Unique among RDB sources                                                   |
| JDBC URL  | yes      | Standard JDBC URL, e.g. `jdbc:postgresql://host:5432/dbname`               |
| SQL query | yes      | Query whose **first column** of each row is treated as the document text   |
| Username  | no       | Encrypted at rest                                                          |
| Password  | no       | Encrypted at rest                                                          |

Username and password are validated as a pair — provide both or neither.
Leaving them blank means the runtime will rely on credentials embedded in the
JDBC URL or the driver's ambient authentication. Credentials are not shown
in the listing table.

#### Read-only safeguard

Relational data sources are intended for **reading documents**, never
mutating them. Before saving, Arbiter scans the SQL for the keywords
**`DELETE`**, **`TRUNCATE`**, and **`DROP`** as whole words
(case-insensitive). If any of those appear, the data source is **rejected**
— nothing is saved — and the admin sees an error like *"SQL query contains
disallowed keyword(s) DELETE. Data sources must use read-only queries."*
The matched keywords plus the offending SQL are recorded in the audit log
under `RDB_DANGEROUS_SQL_BLOCKED` (see the [Audit trail](#audit-trail)
section below).

The match is whole-word-only, so legitimate column names like
`dropoff_count` or `deleted_at` do not trigger the safeguard.

### Local Directory

| Field          | Required | Notes                                                                |
| -------------- | -------- | -------------------------------------------------------------------- |
| Name           | yes      | Unique among local sources                                           |
| Directory path | yes      | Absolute path on the **application server's** filesystem              |
| Filename glob  | yes      | e.g. `*.txt` (top level only), `**.pdf` (any depth — `**` greedily spans `/`)              |

No credentials — the directory is read with the application's process
identity, so make sure the path is reachable and readable by that user.

When the user clicks **Ingest from Local Directory**, the worker walks the
configured path and queues every regular file whose **relative** path
matches the configured glob. `.pdf` files are queued as binary uploads;
everything else is read as UTF-8 text. The job runs as a
[Background Job](../user-guide/background-jobs.md) and reports progress
the same way the OpenSearch/Elasticsearch ingests do. If the directory is
missing, isn't a directory, or isn't readable by the application user, the
job fails up front with a clear error and no documents are queued.

Re-running an import is safe: the worker dedupes by
`(directory path, relative file path)`, so files that have already been
imported from the same directory are recorded as **Skipped** and not
re-enqueued.

## Trust model and host allow-list

Data sources are administered by users with `ROLE_ADMIN`. The product
intentionally **trusts admins** to point Arbiter at internal services —
that's what the feature is for — and the Test buttons issue HTTP / JDBC
calls to whatever URL the admin types. In a typical deployment this is
fine: admins run Arbiter and have at least as much network access as the
application process.

In a multi-tenant or zero-trust deployment where the application server
has access to internal hosts that the admin role should *not* be able to
reach (the [SSRF](https://owasp.org/www-community/attacks/Server_Side_Request_Forgery)
risk), there is a defense-in-depth setting that pins acceptable hosts:

```properties
# application.properties / arbiter-webapp
arbiter.data-sources.allowed-hosts=opensearch.internal,*.search.example.com
```

The format is a comma-separated list. Each entry is either an exact
hostname or a leading-wildcard pattern (`*.foo.com`) that matches one-or-
more left-side labels (so `a.foo.com` and `a.b.foo.com` both match) and
also the bare apex (`foo.com`).

When configured, every URL on every admin **Test** click and every
saved-source ingest job is checked against the list. This covers OpenSearch
/ Elasticsearch data sources, Philter instance endpoints
(**Admin → Philter**), and Ollama instance endpoints
(**Admin → LLM-as-a-Judge**). A non-matching host is rejected with the
error *"Endpoint host is not permitted."* The check applies even to
**already-saved** instances: if a host is removed from the allow-list
later, a Test click on an existing row is also rejected.

### Private-range default-deny

Private-range addresses are blocked by default. Arbiter resolves the
configured hostname and rejects it if it resolves to a loopback address
(`127.x.x.x`), an RFC-1918 private range (`10.x.x.x`, `172.16–31.x.x`,
`192.168.x.x`), or a link-local address (`169.254.x.x`). This applies to
both admin form submissions and Test clicks. Numeric IP addresses in the
URL are evaluated directly using `InetAddress.getByName(...)`, so a
literal private IP (e.g. `http://127.0.0.1:9200` or
`http://169.254.169.254/`) cannot bypass the check by skipping DNS
resolution.

The reason this exists by default — even before any operator has set
`arbiter.data-sources.allowed-hosts` — is **server-side request forgery
(SSRF)**. The application process can usually reach private addresses
the operator does not intend to expose through admin-supplied URLs:
cloud instance-metadata endpoints (`169.254.169.254` on AWS / GCP /
Azure, which can leak role credentials), MongoDB / Redis / Philter on
loopback, intranet HTTP services on RFC-1918, and other tenants on the
same VPC. The default-deny on private ranges closes that surface
without requiring any configuration.

The default-deny is **not absolute**. There are two ways to admit a
private host:

1. **Add it to `arbiter.data-sources.allowed-hosts`.** When the
   property is set, Arbiter checks the resolved host against every
   pattern — including private addresses. So
   `arbiter.data-sources.allowed-hosts=opensearch.internal,192.168.1.100`
   admits exactly those two hosts (one of which is private). Hostnames
   that resolve to a private address are admitted the same way, as long
   as the hostname matches a pattern. This is the supported way to
   reach a legitimate internal OpenSearch / Elasticsearch / Philter /
   Ollama from the data-source UI.
2. **Disable the allow-list from Admin → Security.** The master switch
   bypasses both the pattern check *and* the private-range default-deny
   for every outbound call. This is documented as not recommended — see
   [Master switch (Admin → Security)](#master-switch-admin-security)
   below and the
   [Security settings explanation](security.md#data-source-host-allow-list)
   for the SSRF rationale.

### Allow-list behavior summary

| `arbiter.data-sources.allowed-hosts` | Public host | Private host |
| --- | --- | --- |
| unset / blank (default) | accepted | rejected |
| set, host matches a pattern | accepted | accepted |
| set, host doesn't match | rejected | rejected |

Use the property to restrict which public hosts are reachable in
multi-tenant or zero-trust deployments, or to admit specific private
hosts (an internal OpenSearch, a bastion-routed Philter) the operator
genuinely needs to reach.

### Master switch (Admin → Security)

The whole allow-list — both the property-driven host patterns *and* the
private-range default-deny — can be turned off from
**Admin → Security → Enable host allow-list**. The setting is stored on
the global settings document, defaults to **enabled**, and is read at
request time, so a toggle takes effect on the next outbound check
without restarting.

Disabling the master switch is documented as **not recommended**: it
re-opens the SSRF surface that this allow-list closes. See
[Security settings → Data-source host allow-list](security.md#data-source-host-allow-list)
for the rationale and the audit-trail entry that records each toggle.

## Credential encryption

Every credential that admins type into the Data Sources page is encrypted with
AES-GCM before being written to MongoDB. The same scheme protects Philter API
keys — see [Security · Philter API keys](../security.md#philter-api-keys) for
the full description (key derivation, IV format, base64 layout, and the
`arbiter.crypto.secret` property). The plaintext is never displayed back; the
table only shows a status (`Configured` / `Ambient` / `From URL` /
`••••••`).

The OpenSearch **username** is the one credential field stored as plaintext
on the document row. Treat it as you would any other identifier in the
database — Mongo-level encryption-at-rest still applies, but the value is
visible in raw documents.

## Removing a source

Each row has a **Remove** button that deletes the source after a confirmation
prompt. Removal is hard-delete; nothing is moved to a trash collection.

## Audit trail

Every change is recorded in the [audit log](audit-log.md) with the actor's
email and the affected source's id and name:

| Action                                | Resource                   | When fired                              |
| ------------------------------------- | -------------------------- | --------------------------------------- |
| `OPENSEARCH_DATASOURCE_CREATE`        | `OpenSearchDataSource`     | OpenSearch source added                  |
| `OPENSEARCH_DATASOURCE_UPDATE`        | `OpenSearchDataSource`     | OpenSearch source edited (payload includes a `passwordChanged` boolean) |
| `OPENSEARCH_DATASOURCE_DELETE`        | `OpenSearchDataSource`     | OpenSearch source removed                |
| `ELASTICSEARCH_DATASOURCE_CREATE`     | `ElasticsearchDataSource`  | Elasticsearch source added               |
| `ELASTICSEARCH_DATASOURCE_UPDATE`     | `ElasticsearchDataSource`  | Elasticsearch source edited              |
| `ELASTICSEARCH_DATASOURCE_DELETE`     | `ElasticsearchDataSource`  | Elasticsearch source removed             |
| `S3_DATASOURCE_CREATE`                | `S3DataSource`             | S3 source added                          |
| `S3_DATASOURCE_DELETE`                | `S3DataSource`             | S3 source removed                        |
| `RDB_DATASOURCE_CREATE`               | `RelationalDbDataSource`   | Relational database source added         |
| `RDB_DATASOURCE_DELETE`               | `RelationalDbDataSource`   | Relational database source removed       |
| `RDB_DANGEROUS_SQL_BLOCKED`           | `RelationalDbDataSource`   | RDB source rejected for containing `DELETE`, `TRUNCATE`, or `DROP` in the SQL — entityId is `null` because nothing was saved. The payload includes the data-source name, JDBC URL, matched `keywords`, and the offending `sqlQuery`. |
| `LOCAL_DATASOURCE_CREATE`             | `LocalDirectoryDataSource` | Local directory source added             |
| `LOCAL_DATASOURCE_DELETE`             | `LocalDirectoryDataSource` | Local directory source removed           |

The `*_CREATE` and `*_UPDATE` entries record the connection details
(endpoint / bucket / URL / path, query, table, glob, plus **text field**
and **filename field** for OpenSearch and Elasticsearch) along with a
boolean `credentialsSet` / `passwordSet` flag. The encrypted credential
values themselves are **never logged**. The **Test** actions do not
produce their own audit event — they don't save anything.
