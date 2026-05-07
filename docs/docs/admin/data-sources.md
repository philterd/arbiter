# Data sources

Data sources let admins point Arbiter at an external place where documents
already live — an OpenSearch index, an S3 bucket, a relational database, or a
local filesystem directory — instead of (or in addition to) uploading files
one at a time. Once registered, a data source becomes selectable on the
**Add Documents** page.

The page is at `/admin/data-sources`, also reachable from the **Data Sources**
link in the sidebar's Administration section. ROLE_ADMIN only.

> **Status:** today the page lets admins **register, view, and remove** data
> sources, and they appear as ingest options on **Add Documents**. The actual
> read-from-source ingest is **not implemented yet** — submitting one of the
> data-source forms on the Add Documents page returns a
> *"…is not yet implemented"* notice.

## Source types

The page is split into four sections, one per type. Names must be unique
*within* a section — so an OpenSearch source and an S3 source can both be
named `archive` without conflict.

### OpenSearch

| Field    | Required | Notes                                                                         |
| -------- | -------- | ----------------------------------------------------------------------------- |
| Name     | yes      | Display name; unique among OpenSearch sources                                  |
| Endpoint | yes      | Cluster URL, e.g. `http://localhost:9200`                                      |
| Query    | yes      | Query that returns the documents to import — typically the JSON body of an `_search` request, prefixed with the index path (e.g. `contracts/_search { "query": { "match_all": {} } }`) |
| Username | no       | Stored as plaintext on the document row                                        |
| Password | no       | Encrypted at rest; see [Credential encryption](#credential-encryption)         |

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
Either way, registered sources show **Configured** or **Ambient** in the
Credentials column.

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
JDBC URL or the driver's ambient authentication.

### Local Directory

| Field          | Required | Notes                                                                |
| -------------- | -------- | -------------------------------------------------------------------- |
| Name           | yes      | Unique among local sources                                           |
| Directory path | yes      | Absolute path on the **application server's** filesystem              |
| Filename glob  | yes      | e.g. `*.txt`, `**/*.pdf`                                              |

No credentials — the directory is read with the application's process
identity, so make sure the path is reachable and readable by that user.

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

| Action                          | Resource                | When fired                              |
| ------------------------------- | ----------------------- | --------------------------------------- |
| `OPENSEARCH_DATASOURCE_CREATE`  | `OpenSearchDataSource`  | OpenSearch source added                  |
| `OPENSEARCH_DATASOURCE_DELETE`  | `OpenSearchDataSource`  | OpenSearch source removed                |
| `S3_DATASOURCE_CREATE`          | `S3DataSource`          | S3 source added                          |
| `S3_DATASOURCE_DELETE`          | `S3DataSource`          | S3 source removed                        |
| `RDB_DATASOURCE_CREATE`         | `RelationalDbDataSource`| Relational database source added         |
| `RDB_DATASOURCE_DELETE`         | `RelationalDbDataSource`| Relational database source removed       |
| `LOCAL_DATASOURCE_CREATE`       | `LocalDirectoryDataSource` | Local directory source added         |
| `LOCAL_DATASOURCE_DELETE`       | `LocalDirectoryDataSource` | Local directory source removed       |

The `*_CREATE` entries record the connection details (endpoint / bucket / URL
/ path, query, table, glob, etc.) along with a boolean `credentialsSet` /
`passwordSet` flag. The encrypted credential values themselves are **never
logged**.
