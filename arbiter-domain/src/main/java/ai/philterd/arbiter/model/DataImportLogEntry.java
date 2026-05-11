/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One row per file or document processed by a data import {@link BackgroundJob}. Surfaces
 * the per-file outcome — successful enqueue, skipped (already imported), or failed (with a
 * short reason) — so the operator can see exactly which files a job touched and what
 * happened to each, without grepping container logs.
 *
 * <p>Stored in the {@code data_import_log_entries} collection and indexed by {@code jobId}
 * so the Background Jobs page can render a job's entries in chronological order.
 */
@Document(collection = "data_import_log_entries")
public class DataImportLogEntry {

    /** Successful import — the file's content was enqueued and a Document row was created. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** Skipped because (sourceIndex, sourceDocId) already existed. */
    public static final String OUTCOME_SKIPPED = "SKIPPED";
    /** Per-file failure (read, decode, enqueue) — the job overall may still succeed. */
    public static final String OUTCOME_FAILED = "FAILED";

    @Id
    private String id;

    @Indexed
    private String jobId;

    /** Filename or short label that identifies the file/document on the source side. */
    private String filename;

    /**
     * Optional source-side identifier (e.g. OpenSearch hit id, S3 object key, relative
     * path under a local directory). Set when available so an investigator can trace a
     * log row back to the exact source object.
     */
    private String sourceDocId;

    /** One of {@link #OUTCOME_SUCCESS}, {@link #OUTCOME_SKIPPED}, {@link #OUTCOME_FAILED}. */
    private String outcome;

    /** Human-readable detail. For SUCCESS / SKIPPED this is usually null; for FAILED it carries the reason. */
    private String message;

    /**
     * Wall-clock timestamp when the entry was recorded. Used to order rows in the modal.
     */
    private Instant timestamp;

    public DataImportLogEntry() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(final String jobId) { this.jobId = jobId; }

    public String getFilename() { return filename; }
    public void setFilename(final String filename) { this.filename = filename; }

    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(final String sourceDocId) { this.sourceDocId = sourceDocId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(final String outcome) { this.outcome = outcome; }

    public String getMessage() { return message; }
    public void setMessage(final String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
}
