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
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks the progress of any long-running background job so the user can watch it from the
 * Background Jobs page. The single {@code background_jobs} MongoDB collection backs every
 * job kind — different kinds declare their own {@link #type} value, and {@link #categoryFor}
 * groups types into coarser categories that the UI uses to render separate sections (e.g.
 * "Data Import Jobs", "Cleanup Jobs", …). Type-specific fields like {@link #totalDocuments}
 * or {@link #batchId} are nullable / zero for jobs that don't use them.
 */
/**
 * Partial unique index that enforces "at most one RUNNING data-import job per batch"
 * atomically at the storage layer. PENDING jobs are intentionally not part of the
 * filter — users can queue any number of imports per batch and the dispatcher
 * promotes them to RUNNING one at a time. Completed/failed rows are excluded so
 * historical jobs never collide. Supported by MongoDB 6.0+ (where {@code $in}
 * became valid in {@code partialFilterExpression}).
 */
@Document(collection = "background_jobs")
@CompoundIndex(name = "uniq_running_data_import_per_batch",
        def = "{'batchId': 1}",
        unique = true,
        partialFilter = "{ 'status': 'RUNNING', "
                + "'type': { $in: ['OPENSEARCH_INGEST', 'ELASTICSEARCH_INGEST'] } }")
public class BackgroundJob {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    // ---- Type constants. Add a new TYPE_* and map it via categoryFor() to declare a new
    //      kind of background job. ---------------------------------------------------------

    /** OpenSearch ingest. */
    public static final String TYPE_OPENSEARCH_INGEST = "OPENSEARCH_INGEST";
    /** Elasticsearch ingest — mirrors OpenSearch since the wire protocol is the same. */
    public static final String TYPE_ELASTICSEARCH_INGEST = "ELASTICSEARCH_INGEST";

    // ---- Category constants. A category groups related types so the Background Jobs page
    //      can render a section per category. -------------------------------------------------

    /** Imports documents from an external data source into the redaction queue. */
    public static final String CATEGORY_DATA_IMPORT = "DATA_IMPORT";
    /** Fallback for types that haven't declared a category. */
    public static final String CATEGORY_OTHER = "OTHER";

    /**
     * Map a {@link #type} value to its category. New job kinds add a {@code TYPE_*} constant
     * above and an entry in this switch. Unknown / future types fall back to
     * {@link #CATEGORY_OTHER} so they still render somewhere on the Background Jobs page.
     */
    public static String categoryFor(final String type) {
        if (type == null) return CATEGORY_OTHER;
        return switch (type) {
            case TYPE_OPENSEARCH_INGEST, TYPE_ELASTICSEARCH_INGEST -> CATEGORY_DATA_IMPORT;
            default -> CATEGORY_OTHER;
        };
    }

    /** Human-readable label per category, used as the section heading on the Jobs page. */
    public static String categoryLabel(final String category) {
        if (category == null) return "Other Jobs";
        return switch (category) {
            case CATEGORY_DATA_IMPORT -> "Data Import Jobs";
            default -> "Other Jobs";
        };
    }

    @Id
    private String id;

    /** One of the {@code TYPE_*} constants above. */
    private String type;

    /** Display name of the source the job pulls from (e.g. the OpenSearch data source name). */
    private String sourceName;

    /** Id of the source record (e.g. OpenSearchDataSource id). */
    private String sourceId;

    /** Batch the imported documents are added to. */
    private String batchId;
    private String batchName;

    /** Priority assigned to each enqueued document. */
    private int priority = 2;

    /** One of the {@code STATUS_*} constants. */
    private String status = STATUS_PENDING;

    /** Total number of documents we expect to process. {@code -1} until known. */
    private long totalDocuments = -1;

    /** Documents successfully enqueued. */
    private long processedDocuments;

    /** Documents that could not be enqueued (e.g. missing text field). */
    private long failedDocuments;

    /**
     * Documents skipped because a row with the same source attribution already exists
     * in MongoDB. Skipped hits are not enqueued; a SKIPPED-status placeholder Document
     * row is written instead so the import attempt is auditable.
     */
    private long skippedDocuments;

    /** Free-text error if the job itself failed. */
    private String errorMessage;

    /**
     * Per-hit failure reasons captured during the run, so the Background Jobs page can show
     * <em>why</em> documents did not enqueue. Capped at {@link #MAX_FAILURE_MESSAGES} to keep
     * the MongoDB document size bounded; further failures still bump {@link #failedDocuments}
     * but their reason is only written to the application log.
     */
    public static final int MAX_FAILURE_MESSAGES = 50;
    private java.util.List<String> failureMessages = new java.util.ArrayList<>();

    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    /** Email of the user who started the job. */
    private String createdBy;

    public BackgroundJob() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(final String type) { this.type = type; }

    /** Convenience accessor — returns {@link #categoryFor(String)} for this job's type. */
    public String getCategory() { return categoryFor(type); }

    public String getSourceName() { return sourceName; }
    public void setSourceName(final String sourceName) { this.sourceName = sourceName; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(final String sourceId) { this.sourceId = sourceId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(final String batchId) { this.batchId = batchId; }

    public String getBatchName() { return batchName; }
    public void setBatchName(final String batchName) { this.batchName = batchName; }

    public int getPriority() { return priority; }
    public void setPriority(final int priority) { this.priority = priority; }

    public String getStatus() { return status; }
    public void setStatus(final String status) { this.status = status; }

    public long getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(final long totalDocuments) { this.totalDocuments = totalDocuments; }

    public long getProcessedDocuments() { return processedDocuments; }
    public void setProcessedDocuments(final long processedDocuments) { this.processedDocuments = processedDocuments; }

    public long getFailedDocuments() { return failedDocuments; }
    public void setFailedDocuments(final long failedDocuments) { this.failedDocuments = failedDocuments; }

    public long getSkippedDocuments() { return skippedDocuments; }
    public void setSkippedDocuments(final long skippedDocuments) { this.skippedDocuments = skippedDocuments; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(final String errorMessage) { this.errorMessage = errorMessage; }

    public java.util.List<String> getFailureMessages() { return failureMessages; }
    public void setFailureMessages(final java.util.List<String> failureMessages) {
        this.failureMessages = failureMessages == null ? new java.util.ArrayList<>() : failureMessages;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(final Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(final Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(final Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(final String createdBy) { this.createdBy = createdBy; }
}
