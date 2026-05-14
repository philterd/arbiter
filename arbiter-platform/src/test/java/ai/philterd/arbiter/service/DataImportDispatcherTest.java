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
import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the dispatcher's two responsibilities: respect the admin-configured
 * concurrency ceiling, and never claim more than one job per batch per tick.
 */
class DataImportDispatcherTest {

    private BackgroundJobRepository jobRepository;
    private MongoOperations mongoOperations;
    private OpenSearchIngestJobService osService;
    private ElasticsearchIngestJobService esService;
    private LocalDirectoryIngestJobService localService;
    private S3IngestJobService s3Service;
    private RdbIngestJobService rdbService;
    private GeneralSettingsService settingsService;
    private DataImportDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        mongoOperations = mock(MongoOperations.class);
        osService = mock(OpenSearchIngestJobService.class);
        esService = mock(ElasticsearchIngestJobService.class);
        localService = mock(LocalDirectoryIngestJobService.class);
        s3Service = mock(S3IngestJobService.class);
        rdbService = mock(RdbIngestJobService.class);
        settingsService = mock(GeneralSettingsService.class);
        // Default: limit = 5 unless a test overrides.
        when(settingsService.load()).thenReturn(settingsWithLimit(5));
        dispatcher = new DataImportDispatcher(jobRepository, mongoOperations,
                osService, esService, localService, s3Service, rdbService, settingsService);
    }

    private static GeneralSettings settingsWithLimit(final int limit) {
        final GeneralSettings s = new GeneralSettings();
        s.setMaxConcurrentDataImports(limit);
        return s;
    }

    private static BackgroundJob pendingJob(final String id, final String batchId, final String type) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setBatchId(batchId);
        j.setType(type);
        j.setStatus(BackgroundJob.STATUS_PENDING);
        j.setCreatedAt(Instant.now());
        return j;
    }

    @Test
    void noPendingJobsIsANoOp() {
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        dispatcher.dispatch();

        verify(mongoOperations, never())
                .findAndModify(any(Query.class), any(Update.class),
                        any(FindAndModifyOptions.class), eq(BackgroundJob.class));
    }

    @Test
    void claimsUpToConcurrencyLimit() {
        // 3 PENDING jobs across 3 batches, limit 2 → only 2 claimed this tick.
        final BackgroundJob a = pendingJob("a", "batchA", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        final BackgroundJob b = pendingJob("b", "batchB", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        final BackgroundJob c = pendingJob("c", "batchC", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        when(settingsService.load()).thenReturn(settingsWithLimit(2));
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(a, b, c));
        when(mongoOperations.count(any(Query.class), eq(BackgroundJob.class))).thenReturn(0L);
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenAnswer(inv -> {
                    // Echo back a RUNNING job so the worker has something to dispatch on.
                    final BackgroundJob running = pendingJob("claimed", "batchA",
                            BackgroundJob.TYPE_OPENSEARCH_INGEST);
                    running.setStatus(BackgroundJob.STATUS_RUNNING);
                    return running;
                });

        dispatcher.dispatch();

        verify(mongoOperations, atMost(2)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class));
        verify(mongoOperations, atLeastOnce()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class));
    }

    @Test
    void respectsAlreadyRunningCountTowardLimit() {
        // limit 3, but 3 RUNNING already → no new claims.
        final BackgroundJob a = pendingJob("a", "batchA", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        when(settingsService.load()).thenReturn(settingsWithLimit(3));
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(a));
        when(mongoOperations.count(any(Query.class), eq(BackgroundJob.class))).thenReturn(3L);

        dispatcher.dispatch();

        verify(mongoOperations, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class));
    }

    @Test
    void claimsOnlyOnePendingPerBatchPerTick() {
        // Two PENDING jobs for the same batch + plenty of slots → only one claim.
        final BackgroundJob a1 = pendingJob("a1", "batchA", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        final BackgroundJob a2 = pendingJob("a2", "batchA", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        when(settingsService.load()).thenReturn(settingsWithLimit(10));
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(a1, a2));
        when(mongoOperations.count(any(Query.class), eq(BackgroundJob.class))).thenReturn(0L);
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenAnswer(inv -> {
                    final BackgroundJob running = pendingJob("a1", "batchA",
                            BackgroundJob.TYPE_OPENSEARCH_INGEST);
                    running.setStatus(BackgroundJob.STATUS_RUNNING);
                    return running;
                });

        dispatcher.dispatch();

        verify(mongoOperations).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class));
    }

    @Test
    void duplicateKeyExceptionLeavesJobPending() {
        // Mongo throws on the partial unique index — dispatcher must swallow it and not
        // submit anything to the worker pool. (Without the catch this would propagate
        // out of the @Scheduled method, which Spring would log noisily.)
        final BackgroundJob a = pendingJob("a", "batchA", BackgroundJob.TYPE_OPENSEARCH_INGEST);
        when(settingsService.load()).thenReturn(settingsWithLimit(5));
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(a));
        when(mongoOperations.count(any(Query.class), eq(BackgroundJob.class))).thenReturn(0L);
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        dispatcher.dispatch();

        verify(osService, never()).run(any());
        verify(esService, never()).run(any());
    }

    @Test
    void dispatchesElasticsearchJobsToTheEsService() {
        final BackgroundJob a = pendingJob("a", "batchA", BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
        when(settingsService.load()).thenReturn(settingsWithLimit(5));
        when(jobRepository.findByStatusAndTypeInOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(a));
        when(mongoOperations.count(any(Query.class), eq(BackgroundJob.class))).thenReturn(0L);
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenAnswer(inv -> {
                    final BackgroundJob running = pendingJob("a", "batchA",
                            BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
                    running.setStatus(BackgroundJob.STATUS_RUNNING);
                    return running;
                });

        dispatcher.dispatch();

        // The worker pool runs the claimed job on a separate thread; allow time.
        verify(esService, timeout(2000)).run("a");
        verify(osService, never()).run(any());
    }
}
