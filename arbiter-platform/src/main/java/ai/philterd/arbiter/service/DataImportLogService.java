/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.DataImportLogEntry;
import ai.philterd.arbiter.repository.DataImportLogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single write surface for per-file data-import log entries. Each ingest service
 * (OpenSearch, Elasticsearch, local directory, S3) calls one of {@link #success},
 * {@link #skipped}, or {@link #failed} as it processes each source object so the
 * Background Jobs page can render a per-job log of what happened to which file.
 *
 * <p>Persist failures are swallowed: this is a side effect of the primary import work,
 * so a transient Mongo glitch on a log write must not abort the running job.
 */
@Service
public class DataImportLogService {

    private static final Logger log = LoggerFactory.getLogger(DataImportLogService.class);

    private final DataImportLogEntryRepository repository;

    public DataImportLogService(final DataImportLogEntryRepository repository) {
        this.repository = repository;
    }

    /** Successful enqueue of a single file/object. */
    public void success(final String jobId, final String filename, final String sourceDocId) {
        record(jobId, filename, sourceDocId, DataImportLogEntry.OUTCOME_SUCCESS, null);
    }

    /** Skipped because the (sourceIndex, sourceDocId) pair already had a Document. */
    public void skipped(final String jobId, final String filename, final String sourceDocId) {
        record(jobId, filename, sourceDocId, DataImportLogEntry.OUTCOME_SKIPPED, null);
    }

    /** Per-file failure — the job as a whole may still complete with other files succeeding. */
    public void failed(final String jobId, final String filename, final String sourceDocId,
                       final String reason) {
        record(jobId, filename, sourceDocId, DataImportLogEntry.OUTCOME_FAILED, reason);
    }

    /** List rows for a job in the order they were recorded. */
    public List<DataImportLogEntry> forJob(final String jobId) {
        return repository.findByJobIdOrderByTimestampAsc(jobId);
    }

    /**
     * Paged variant for the Background Jobs UI. {@code page} is zero-based and clamped
     * to a non-negative value; {@code size} is clamped to {@code [1, 200]} so a malicious
     * or accidental large {@code size} can't pull the entire collection in one shot.
     */
    public Page<DataImportLogEntry> forJob(final String jobId, final int page, final int size) {
        final int safePage = Math.max(0, page);
        final int safeSize = Math.min(200, Math.max(1, size));
        return repository.findByJobIdOrderByTimestampAsc(jobId,
                PageRequest.of(safePage, safeSize));
    }

    /**
     * Delete every log entry that belongs to one of the given job ids. Used by the
     * Admin Tools "Clean up data import jobs" flow which collects ids of terminal
     * jobs and removes their per-file logs before deleting the job rows themselves.
     * Returns the number of entries actually removed so the caller can report it.
     */
    public long deleteByJobIds(final java.util.Collection<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return 0L;
        return repository.deleteByJobIdIn(jobIds);
    }

    private void record(final String jobId, final String filename, final String sourceDocId,
                        final String outcome, final String message) {
        if (jobId == null || jobId.isBlank()) return;
        try {
            final DataImportLogEntry entry = new DataImportLogEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setJobId(jobId);
            entry.setFilename(filename == null ? "" : filename);
            entry.setSourceDocId(sourceDocId);
            entry.setOutcome(outcome);
            entry.setMessage(message);
            entry.setTimestamp(Instant.now());
            repository.save(entry);
        } catch (RuntimeException e) {
            log.warn("Failed to write data-import log entry for job {} (file {}): {}",
                    jobId, filename, e.getMessage());
        }
    }
}
