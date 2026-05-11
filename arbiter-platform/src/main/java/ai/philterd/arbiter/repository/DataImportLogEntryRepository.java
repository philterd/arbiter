/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.DataImportLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DataImportLogEntryRepository extends MongoRepository<DataImportLogEntry, String> {

    /**
     * Return every log entry for one import job, ordered oldest-first so the UI renders
     * them in the same sequence the worker recorded them. Indexed by {@code jobId} on
     * the model so this is a single-key lookup.
     */
    List<DataImportLogEntry> findByJobIdOrderByTimestampAsc(String jobId);

    /**
     * Paged variant used by the Background Jobs log modal so a job with thousands of
     * file rows doesn't dump everything to the browser at once.
     */
    Page<DataImportLogEntry> findByJobIdOrderByTimestampAsc(String jobId, Pageable pageable);

    /**
     * Bulk-delete every entry for a job — invoked when the parent {@link
     * ai.philterd.arbiter.model.BackgroundJob} row is dropped so the log collection
     * doesn't accumulate orphan rows.
     */
    void deleteByJobId(String jobId);

    /**
     * Bulk-delete log entries for the given job ids. Used by the Admin Tools cleanup
     * flow which collects the ids of terminal data-import jobs and removes their
     * logs in one shot.
     */
    long deleteByJobIdIn(java.util.Collection<String> jobIds);
}
