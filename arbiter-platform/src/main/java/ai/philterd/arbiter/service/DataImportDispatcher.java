/*
 * Copyright 2026 Philterd
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
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Promotes PENDING data-import jobs to RUNNING one batch at a time. Multiple PENDING
 * jobs may sit in the queue for the same batch — they execute in oldest-first order,
 * each waiting for the prior to finish.
 *
 * <p>The dispatcher runs on a periodic poll. For each PENDING data-import job
 * (oldest first) it attempts a {@code findAndModify} from PENDING to RUNNING. The
 * {@code uniq_running_data_import_per_batch} partial unique index on
 * {@link BackgroundJob} causes the update to fail with {@code DuplicateKeyException}
 * when another RUNNING job already exists for that batch — the job simply remains
 * PENDING and the dispatcher tries again on the next tick. This pattern is
 * multi-replica safe: any number of replicas can run the dispatcher and the index
 * guarantees only one of them ever holds the running slot for a batch.
 */
@Service
public class DataImportDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DataImportDispatcher.class);

    private static final List<String> DATA_IMPORT_TYPES = List.of(
            BackgroundJob.TYPE_OPENSEARCH_INGEST,
            BackgroundJob.TYPE_ELASTICSEARCH_INGEST);

    private final BackgroundJobRepository jobRepository;
    private final MongoOperations mongoOperations;
    private final OpenSearchIngestJobService osService;
    private final ElasticsearchIngestJobService esService;
    private final GeneralSettingsService generalSettingsService;
    /**
     * Pool that runs claimed jobs. Sized to the upper bound of the admin-configurable
     * concurrency limit — the actual ceiling each tick is read from
     * {@link GeneralSettingsService#load()}.
     */
    private final ExecutorService workerPool;

    public DataImportDispatcher(final BackgroundJobRepository jobRepository,
                                final MongoOperations mongoOperations,
                                final OpenSearchIngestJobService osService,
                                final ElasticsearchIngestJobService esService,
                                final GeneralSettingsService generalSettingsService) {
        this.jobRepository = jobRepository;
        this.mongoOperations = mongoOperations;
        this.osService = osService;
        this.esService = esService;
        this.generalSettingsService = generalSettingsService;
        this.workerPool = Executors.newFixedThreadPool(
                GeneralSettingsService.MAX_CONCURRENT_DATA_IMPORTS, r -> {
                    final Thread t = new Thread(r, "data-import-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Scheduled(fixedDelayString = "${arbiter.data-import.poll-millis:2000}",
            initialDelayString = "${arbiter.data-import.initial-delay-millis:5000}")
    public void dispatch() {
        final List<BackgroundJob> pending = jobRepository
                .findByStatusAndTypeInOrderByCreatedAtAsc(
                        BackgroundJob.STATUS_PENDING, DATA_IMPORT_TYPES);
        if (pending.isEmpty()) return;

        // The admin-controlled global ceiling on concurrent data-import jobs. Any
        // RUNNING jobs already in flight count against the budget. Counting via Mongo
        // (rather than an in-memory tally) keeps this multi-replica safe.
        final int limit = generalSettingsService.load().getMaxConcurrentDataImports();
        final long currentRunning = mongoOperations.count(
                Query.query(Criteria.where("status").is(BackgroundJob.STATUS_RUNNING)
                        .and("type").in(DATA_IMPORT_TYPES)),
                BackgroundJob.class);
        int slotsRemaining = (int) Math.max(0L, limit - currentRunning);
        if (slotsRemaining == 0) return;

        // Track which batches we've claimed in this tick. Without this we'd retry
        // every PENDING for the same batch (each retry losing on the unique index).
        // Walking oldest-first means the first PENDING per batch wins this tick;
        // subsequent PENDINGs for that batch wait for the next tick after the
        // current run finishes.
        final Set<String> batchesClaimedThisTick = new HashSet<>();
        for (BackgroundJob candidate : pending) {
            if (slotsRemaining == 0) break;
            if (candidate.getBatchId() == null
                    || batchesClaimedThisTick.contains(candidate.getBatchId())) {
                continue;
            }
            final BackgroundJob claimed = tryClaim(candidate.getId());
            if (claimed != null) {
                batchesClaimedThisTick.add(claimed.getBatchId());
                slotsRemaining--;
                workerPool.submit(() -> runClaimed(claimed));
            }
        }
    }

    /**
     * Atomic PENDING→RUNNING flip. Returns the updated job on success, null when the
     * job has already been claimed by someone else or when the partial unique index
     * rejects the transition because another job is already RUNNING for the batch.
     */
    private BackgroundJob tryClaim(final String jobId) {
        try {
            final Query q = Query.query(Criteria.where("_id").is(jobId)
                    .and("status").is(BackgroundJob.STATUS_PENDING));
            final Update u = new Update()
                    .set("status", BackgroundJob.STATUS_RUNNING)
                    .set("startedAt", Instant.now());
            return mongoOperations.findAndModify(q, u,
                    FindAndModifyOptions.options().returnNew(true), BackgroundJob.class);
        } catch (DuplicateKeyException e) {
            log.debug("Job {} could not be claimed — another RUNNING import exists for the batch.",
                    jobId);
            return null;
        }
    }

    private void runClaimed(final BackgroundJob job) {
        try {
            switch (job.getType() == null ? "" : job.getType()) {
                case BackgroundJob.TYPE_OPENSEARCH_INGEST -> osService.run(job.getId());
                case BackgroundJob.TYPE_ELASTICSEARCH_INGEST -> esService.run(job.getId());
                default -> log.warn("DataImportDispatcher: unknown job type {} for job {}",
                        job.getType(), job.getId());
            }
        } catch (Exception e) {
            log.error("DataImportDispatcher: job {} threw an unexpected exception",
                    job.getId(), e);
        }
    }
}
