/*
 * Copyright 2026 Philterd
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
}
