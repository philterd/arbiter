# Database

Philter Workspace stores its data (batches, documents, spans, users, the audit log, and
settings) in one of two databases. Choose one with `philter-workspace.store` (or the
`PHILTER_WORKSPACE_STORE` environment variable):

| `philter-workspace.store` | Database | Use it for |
| --------------- | -------- | ---------- |
| `mongodb` (default) | A MongoDB server | Deployments that run more than one Philter Workspace instance, or that already operate MongoDB. |
| `sqlite` | A single file on the Philter Workspace host | Single-node deployments, including air-gapped ones, that would rather not run a database server. |

Both stores support the same features. Philter Workspace refuses to start if
`philter-workspace.store` is set to anything else.

The bundled `docker-compose.yaml` uses SQLite, with the database in the
`philter-workspace-data` volume. To run it on MongoDB instead, start the MongoDB service
with its profile:

```
PHILTER_WORKSPACE_STORE=mongodb docker compose --profile mongodb up
```

`docker-compose.prod.yaml` runs Philter Workspace and Philter on one host, each with
its own SQLite database in a volume (`philter-workspace-data` and `philter-data`). The
comments at the top of the file list the secrets it needs in `.env`.

Valkey is still required for sessions with either store. See
[Configuration](configuration.md#sessions-and-valkey).

## MongoDB

Set `spring.mongodb.uri` (`SPRING_MONGODB_URI`) to the server's connection URI. Philter Workspace creates
its collections and indexes on startup. See
[Configuration](configuration.md#database) for the related settings.

## SQLite

With `philter-workspace.store=sqlite`, Philter Workspace keeps everything in one SQLite database
file and creates its tables and indexes on startup. No other database software
is needed.

| Property | Default | Purpose |
| -------- | ------- | ------- |
| `philter-workspace.sqlite.path` | `./data/philter-workspace.db` | The database file. Philter Workspace creates the directory if it is missing. |
| `philter-workspace.sqlite.pool-size` | `8` | Connections to the file. Reads run in parallel; writes take turns. |
| `philter-workspace.sqlite.busy-timeout-ms` | `30000` | How long a write waits for another write to finish before it fails. |
| `philter-workspace.sqlite.synchronous` | `FULL` | SQLite's `synchronous` setting. `FULL` writes each commit to disk before it returns, so a committed change survives a power loss. `NORMAL` is faster, but the most recent commits can be lost on power loss. |

Each property can also be set as an environment variable, for example
`PHILTER_WORKSPACE_SQLITE_PATH`.

### Running one instance

A SQLite database belongs to one Philter Workspace process. Run a single Philter Workspace
instance against a given file, and keep the file on a local disk: SQLite's
locking is not reliable on network file systems such as NFS or SMB. To run
several Philter Workspace instances behind a load balancer, use MongoDB.

Philter Workspace uses SQLite's write-ahead log, so the database is three files: the
`philter-workspace.db` file and, while Philter Workspace is running, `philter-workspace.db-wal` and
`philter-workspace.db-shm` next to it. Keep all three together.

### In a container

The container's working directory is `/app`, so the default file is
`/app/data/philter-workspace.db`. Mount a volume at `/app/data` so the database
outlives the container:

```
docker run -e PHILTER_WORKSPACE_STORE=sqlite -v philter-workspace-data:/app/data ...
```

### Backups

Back up a running instance with SQLite's online backup, which produces a
consistent copy while Philter Workspace keeps working:

```
sqlite3 /app/data/philter-workspace.db ".backup '/backups/philter-workspace-$(date +%F).db'"
```

`VACUUM INTO '/backups/philter-workspace.db'` from the `sqlite3` shell does the same and
also compacts the copy. Copying `philter-workspace.db` with ordinary file tools is safe
only while Philter Workspace is stopped, and must include any `-wal` file.

Restore by stopping Philter Workspace and putting the backup in place of `philter-workspace.db`
(and removing any leftover `-wal` and `-shm` files).

### Encryption at rest

Document text, detected PII, comments, and the other fields listed in
[Security](../security.md#pii-at-rest) are encrypted by Philter Workspace before they are
written, exactly as with MongoDB. The rest of the file (names, statuses,
timestamps, email addresses of users) is not. Place the file on an encrypted
volume to protect it as a whole, and restrict file permissions to the account
Philter Workspace runs as.

### Inspecting the data

Each collection is a table with `id`, `version`, and `doc` columns, where
`doc` is the record as JSON. SQLite's JSON functions read it, for example:

```
SELECT json_extract(doc, '$.status'), COUNT(*) FROM documents GROUP BY 1;
```

Change data through Philter Workspace rather than by editing the file. Philter Workspace relies on
the `version` column to detect conflicting edits.

## Upgrading

### One running import per batch

Philter Workspace runs at most one data import per batch at a time, enforced by a
unique index on running import jobs. Earlier versions left S3 imports out of
that index (`uniq_running_data_import_per_batch`). The corrected index is named
`uniq_running_data_import_per_batch_v2` (`ix_background_jobs_uniq_running_data_import_per_batch_v2`
in SQLite), and both stores create it on startup.

Philter Workspace cannot create the new index, and does not start, if a batch already
has more than one running import, which the old index allowed when one of them
was an S3 import. Before upgrading, stop every Philter Workspace instance and look for
such batches.

MongoDB:

```
db.background_jobs.aggregate([
  { $match: { status: "RUNNING", type: { $in: ["OPENSEARCH_INGEST", "ELASTICSEARCH_INGEST",
      "LOCAL_DIRECTORY_INGEST", "S3_INGEST", "RDB_INGEST"] } } },
  { $group: { _id: "$batchId", jobs: { $push: "$_id" }, count: { $sum: 1 } } },
  { $match: { count: { $gt: 1 } } }
])
```

SQLite:

```
SELECT json_extract(doc, '$.batchId') AS batch, group_concat(id) AS jobs
FROM background_jobs
WHERE json_extract(doc, '$.status') = 'RUNNING'
  AND json_extract(doc, '$.type') IN ('OPENSEARCH_INGEST', 'ELASTICSEARCH_INGEST',
      'LOCAL_DIRECTORY_INGEST', 'S3_INGEST', 'RDB_INGEST')
GROUP BY batch HAVING COUNT(*) > 1;
```

With every instance stopped, none of these jobs is still running, and a job
left marked running would keep blocking its batch's imports. Mark each listed
job failed, replacing `<job id>`:

```
db.background_jobs.updateOne({ _id: "<job id>" },
  { $set: { status: "FAILED", errorMessage: "Interrupted; marked failed before upgrading." } })
```

```
UPDATE background_jobs
SET doc = json_set(doc, '$.status', 'FAILED', '$.errorMessage', 'Interrupted; marked failed before upgrading.')
WHERE id = '<job id>';
```

Run the import again from **Add Documents** if it is still needed (see
[Adding from a data source](../user-guide/uploading.md#adding-from-a-data-source)).

The old index stays in place after the upgrade. It enforces a subset of the
new one, so it is harmless, and you can drop it:

```
db.background_jobs.dropIndex("uniq_running_data_import_per_batch")
```

```
DROP INDEX IF EXISTS "ix_background_jobs_uniq_running_data_import_per_batch";
```

## Switching stores

Changing `philter-workspace.store` does not move data. An instance switched from MongoDB
to SQLite (or back) starts with an empty database.
