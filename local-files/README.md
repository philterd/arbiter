# Local-directory data-source test fixture

This directory is mounted **read-only** into the `arbiter-app` container at
`/app/local-files` by `docker-compose.yaml`. It exists so that local-directory
data sources can be exercised end-to-end against a Dockerized Arbiter without
mounting an arbitrary host path.

## Trying it out

1. `docker compose up -d`
2. Sign in as an administrator and go to **Admin → Data Sources**.
3. Under **Local Directory**, create a source:
   - **Directory path:** `/app/local-files` (the path **inside** the
     container — not the host path)
   - **Filename glob:** `*.txt` for the sample text files, or `**.pdf` to
     pick up any PDFs you drop in.
4. Go to **Add Documents → Local Directory**, pick a batch, and click
   **Ingest from Local Directory**. Watch the import on the Background Jobs
   page; the ingested documents land in the chosen batch.

Drop additional files in this directory on the host and re-run the ingest
to test the dedupe path — already-imported files are recorded as **Skipped**
rather than re-enqueued.

The files in this directory are intentionally small and contain fake PII
that mirrors what `sample-files/` provides for the bootstrap demo loader.
