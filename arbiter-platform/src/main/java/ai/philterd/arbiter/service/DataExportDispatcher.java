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

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Promotes PENDING batch-export jobs to RUNNING and dispatches them to
 * {@link BatchExportService#runJob}. Mirrors {@link DataImportDispatcher} but
 * for the {@link BackgroundJob#TYPE_BATCH_EXPORT} type.
 *
 * <p>Unlike data imports, exports do not have a per-batch concurrency
 * constraint — multiple exports of the same batch (e.g. to different
 * destinations) can safely run in parallel since they only read documents and
 * write to a destination, never mutating the batch's data. The dispatcher
 * therefore uses a small fixed worker pool and claims jobs in oldest-first
 * order without any per-batch interlock.
 */
@Service
public class DataExportDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DataExportDispatcher.class);

    /**
     * Concurrency cap for export workers. Exports stream into memory before
     * writing, so each worker holds an in-memory buffer up to the export size;
     * keep this conservative to avoid OOM on large exports.
     */
    private static final int MAX_CONCURRENT_EXPORTS = 2;

    private final BackgroundJobRepository jobRepository;
    private final MongoOperations mongoOperations;
    private final BatchExportService batchExportService;
    private final ExecutorService workerPool;

    public DataExportDispatcher(final BackgroundJobRepository jobRepository,
                                final MongoOperations mongoOperations,
                                final BatchExportService batchExportService) {
        this.jobRepository = jobRepository;
        this.mongoOperations = mongoOperations;
        this.batchExportService = batchExportService;
        this.workerPool = Executors.newFixedThreadPool(MAX_CONCURRENT_EXPORTS, r -> {
            final Thread t = new Thread(r, "data-export-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${arbiter.data-export.poll-millis:2000}",
            initialDelayString = "${arbiter.data-export.initial-delay-millis:5000}")
    public void dispatch() {
        final List<BackgroundJob> pending = jobRepository
                .findByStatusAndTypeInOrderByCreatedAtAsc(
                        BackgroundJob.STATUS_PENDING,
                        List.of(BackgroundJob.TYPE_BATCH_EXPORT));
        if (pending.isEmpty()) return;

        final long currentRunning = mongoOperations.count(
                Query.query(Criteria.where("status").is(BackgroundJob.STATUS_RUNNING)
                        .and("type").is(BackgroundJob.TYPE_BATCH_EXPORT)),
                BackgroundJob.class);
        int slotsRemaining = (int) Math.max(0L, MAX_CONCURRENT_EXPORTS - currentRunning);
        if (slotsRemaining == 0) return;

        for (BackgroundJob candidate : pending) {
            if (slotsRemaining == 0) break;
            final BackgroundJob claimed = tryClaim(candidate.getId());
            if (claimed != null) {
                slotsRemaining--;
                workerPool.submit(() -> runClaimed(claimed));
            }
        }
    }

    private BackgroundJob tryClaim(final String jobId) {
        final Query q = Query.query(Criteria.where("_id").is(jobId)
                .and("status").is(BackgroundJob.STATUS_PENDING));
        final Update u = new Update()
                .set("status", BackgroundJob.STATUS_RUNNING)
                .set("startedAt", Instant.now());
        return mongoOperations.findAndModify(q, u,
                FindAndModifyOptions.options().returnNew(true), BackgroundJob.class);
    }

    private void runClaimed(final BackgroundJob job) {
        try {
            batchExportService.runJob(job.getId());
        } catch (Exception e) {
            log.error("DataExportDispatcher: job {} threw an unexpected exception",
                    job.getId(), e);
        }
    }
}
