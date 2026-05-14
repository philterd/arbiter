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

import ai.philterd.arbiter.model.BackgroundJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BackgroundJobRepository extends MongoRepository<BackgroundJob, String> {
    List<BackgroundJob> findAllByOrderByCreatedAtDesc();

    /**
     * Used by ingest services to enforce one-data-import-per-batch: returns true when any
     * job of the given types exists for the batch with a status in the supplied set
     * (typically {@code PENDING} or {@code RUNNING}).
     */
    boolean existsByBatchIdAndStatusInAndTypeIn(String batchId,
                                                Collection<String> statuses,
                                                Collection<String> types);

    /** Used by the dispatcher to find queue candidates oldest-first. */
    List<BackgroundJob> findByStatusAndTypeInOrderByCreatedAtAsc(String status,
                                                                 Collection<String> types);

    /**
     * Admin Tools cleanup looks up terminal jobs (status in COMPLETED/FAILED) for
     * the given import types so it can wipe their log entries before deleting the
     * job rows themselves. Anything still PENDING or RUNNING is intentionally not
     * matched so an in-flight import isn't ripped out from under the worker.
     */
    List<BackgroundJob> findByTypeInAndStatusIn(Collection<String> types,
                                                 Collection<String> statuses);

    /** Bulk-delete variant of {@link #findByTypeInAndStatusIn}, used after log cleanup. */
    long deleteByTypeInAndStatusIn(Collection<String> types, Collection<String> statuses);

    /**
     * Used by the data-source delete path to refuse removal while an in-flight
     * import is still referencing it. Returns true when any job exists whose
     * {@code sourceId} matches and whose status is one of the supplied
     * statuses — pass {@code PENDING}/{@code RUNNING} for the "currently in
     * use" check. Terminal jobs (COMPLETED/FAILED) don't count.
     */
    boolean existsBySourceIdAndStatusIn(String sourceId, Collection<String> statuses);
}
