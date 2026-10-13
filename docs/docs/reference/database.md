# Database

Arbiter stores its data (batches, documents, spans, users, the audit log, and
settings) in one of two databases. Choose one with `arbiter.store` (or the
`ARBITER_STORE` environment variable):

| `arbiter.store` | Database | Use it for |
| --------------- | -------- | ---------- |
| `mongodb` (default) | A MongoDB server | Deployments that run more than one Arbiter instance, or that already operate MongoDB. |
| `sqlite` | A single file on the Arbiter host | Single-node deployments, including air-gapped ones, that would rather not run a database server. |

Both stores support the same features. Arbiter refuses to start if
`arbiter.store` is set to anything else.

The bundled `docker-compose.yaml` uses SQLite, with the database in the
`arbiter-data` volume. To run it on MongoDB instead, start the MongoDB service
with its profile:

```
ARBITER_STORE=mongodb docker compose --profile mongodb up
```

`docker-compose.prod.yaml` runs Arbiter and Philter on one host, each with
its own SQLite database in a volume (`arbiter-data` and `philter-data`). The
comments at the top of the file list the secrets it needs in `.env`.

Valkey is still required for sessions with either store. See
[Configuration](configuration.md#sessions-and-valkey).

## MongoDB

Set `spring.data.mongodb.uri` to the server's connection URI. Arbiter creates
its collections and indexes on first use. See
[Configuration](configuration.md#database) for the related settings.

## SQLite

With `arbiter.store=sqlite`, Arbiter keeps everything in one SQLite database
file and creates its tables and indexes on startup. No other database software
is needed.

| Property | Default | Purpose |
| -------- | ------- | ------- |
| `arbiter.sqlite.path` | `./data/arbiter.db` | The database file. Arbiter creates the directory if it is missing. |
| `arbiter.sqlite.pool-size` | `8` | Connections to the file. Reads run in parallel; writes take turns. |
| `arbiter.sqlite.busy-timeout-ms` | `30000` | How long a write waits for another write to finish before it fails. |
| `arbiter.sqlite.synchronous` | `FULL` | SQLite's `synchronous` setting. `FULL` writes each commit to disk before it returns, so a committed change survives a power loss. `NORMAL` is faster, but the most recent commits can be lost on power loss. |

Each property can also be set as an environment variable, for example
`ARBITER_SQLITE_PATH`.

### Running one instance

A SQLite database belongs to one Arbiter process. Run a single Arbiter
instance against a given file, and keep the file on a local disk: SQLite's
locking is not reliable on network file systems such as NFS or SMB. To run
several Arbiter instances behind a load balancer, use MongoDB.

Arbiter uses SQLite's write-ahead log, so the database is three files: the
`arbiter.db` file and, while Arbiter is running, `arbiter.db-wal` and
`arbiter.db-shm` next to it. Keep all three together.

### In a container

The container's working directory is `/app`, so the default file is
`/app/data/arbiter.db`. Mount a volume at `/app/data` so the database
outlives the container:

```
docker run -e ARBITER_STORE=sqlite -v arbiter-data:/app/data ...
```

### Backups

Back up a running instance with SQLite's online backup, which produces a
consistent copy while Arbiter keeps working:

```
sqlite3 /app/data/arbiter.db ".backup '/backups/arbiter-$(date +%F).db'"
```

`VACUUM INTO '/backups/arbiter.db'` from the `sqlite3` shell does the same and
also compacts the copy. Copying `arbiter.db` with ordinary file tools is safe
only while Arbiter is stopped, and must include any `-wal` file.

Restore by stopping Arbiter and putting the backup in place of `arbiter.db`
(and removing any leftover `-wal` and `-shm` files).

### Encryption at rest

Document text, detected PII, comments, and the other fields listed in
[Security](../security.md#pii-at-rest) are encrypted by Arbiter before they are
written, exactly as with MongoDB. The rest of the file (names, statuses,
timestamps, email addresses of users) is not. Place the file on an encrypted
volume to protect it as a whole, and restrict file permissions to the account
Arbiter runs as.

### Inspecting the data

Each collection is a table with `id`, `version`, and `doc` columns, where
`doc` is the record as JSON. SQLite's JSON functions read it, for example:

```
SELECT json_extract(doc, '$.status'), COUNT(*) FROM documents GROUP BY 1;
```

Change data through Arbiter rather than by editing the file. Arbiter relies on
the `version` column to detect conflicting edits.

## Switching stores

Changing `arbiter.store` does not move data. An instance switched from MongoDB
to SQLite (or back) starts with an empty database.
