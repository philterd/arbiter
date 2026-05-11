/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.DataImportLogEntry;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.DataImportLogEntryRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataImportLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for the Admin → Tools cleanup flow. Unlike
 * {@link AdminToolsControllerTest} (which mocks {@link DataImportLogService}),
 * these tests wire the controller against a real {@link DataImportLogService}
 * backed by an in-memory fake repository, so the service's
 * {@code deleteByJobIds} short-circuit, the controller's id collection step,
 * and the eventual job-row delete all run in concert.
 *
 * <p>The integration coverage targets the load-bearing safety guarantee of the
 * feature: PENDING / RUNNING jobs must survive the cleanup so an in-flight
 * import is never deleted out from under the worker, and log entries belonging
 * to surviving jobs must not be touched.
 */
class AdminToolsCleanupIntegrationTest {

    /** In-memory job store, keyed by id. The fake repository reads + mutates this map. */
    private final Map<String, BackgroundJob> jobs = new HashMap<>();
    /** In-memory log entries, keyed by entry id. */
    private final Map<String, DataImportLogEntry> entries = new HashMap<>();

    private BackgroundJobRepository jobRepository;
    private DataImportLogEntryRepository entryRepository;
    private DataImportLogService importLogService;
    private AuditLogService auditLogService;
    private AdminToolsController controller;

    @BeforeEach
    void setUp() {
        jobs.clear();
        entries.clear();
        jobRepository = mock(BackgroundJobRepository.class);
        entryRepository = mock(DataImportLogEntryRepository.class);
        wireFakeRepositories();
        importLogService = new DataImportLogService(entryRepository);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminToolsController(jobRepository, importLogService, auditLogService);
    }

    /**
     * Stitch the mocked Spring Data repository methods to the in-memory maps so
     * mutations done by one call are visible to the next call.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireFakeRepositories() {
        // BackgroundJobRepository.findByTypeInAndStatusIn — filter the job map.
        when(jobRepository.findByTypeInAndStatusIn(any(Collection.class), any(Collection.class)))
                .thenAnswer(inv -> {
                    final Collection<String> types = inv.getArgument(0);
                    final Collection<String> statuses = inv.getArgument(1);
                    final List<BackgroundJob> matches = new ArrayList<>();
                    for (final BackgroundJob j : jobs.values()) {
                        if (types.contains(j.getType()) && statuses.contains(j.getStatus())) {
                            matches.add(j);
                        }
                    }
                    return matches;
                });
        // BackgroundJobRepository.deleteByTypeInAndStatusIn — remove + return count.
        when(jobRepository.deleteByTypeInAndStatusIn(any(Collection.class), any(Collection.class)))
                .thenAnswer(inv -> {
                    final Collection<String> types = inv.getArgument(0);
                    final Collection<String> statuses = inv.getArgument(1);
                    final List<String> toRemove = new ArrayList<>();
                    for (final BackgroundJob j : jobs.values()) {
                        if (types.contains(j.getType()) && statuses.contains(j.getStatus())) {
                            toRemove.add(j.getId());
                        }
                    }
                    toRemove.forEach(jobs::remove);
                    return (long) toRemove.size();
                });
        // DataImportLogEntryRepository.deleteByJobIdIn — remove + return count.
        when(entryRepository.deleteByJobIdIn(any(Collection.class)))
                .thenAnswer(inv -> {
                    final Collection<String> ids = inv.getArgument(0);
                    final List<String> toRemove = new ArrayList<>();
                    for (final DataImportLogEntry e : entries.values()) {
                        if (ids.contains(e.getJobId())) toRemove.add(e.getId());
                    }
                    toRemove.forEach(entries::remove);
                    return (long) toRemove.size();
                });
        // DataImportLogEntryRepository.save — used only if we record entries via the
        // service directly; just persist into the map.
        when(entryRepository.save(any(DataImportLogEntry.class)))
                .thenAnswer(inv -> {
                    final DataImportLogEntry e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID().toString());
                    entries.put(e.getId(), e);
                    return e;
                });
    }

    private BackgroundJob seedJob(final String id, final String type, final String status) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setType(type);
        j.setStatus(status);
        j.setCreatedAt(Instant.now());
        jobs.put(id, j);
        return j;
    }

    private void seedLogEntries(final String jobId, final int count) {
        for (int i = 0; i < count; i++) {
            final DataImportLogEntry e = new DataImportLogEntry();
            e.setId(jobId + "-e-" + i);
            e.setJobId(jobId);
            e.setFilename("file-" + i + ".txt");
            e.setOutcome(DataImportLogEntry.OUTCOME_SUCCESS);
            e.setTimestamp(Instant.now());
            entries.put(e.getId(), e);
        }
    }

    private static RedirectAttributes flash() {
        return new RedirectAttributesModelMap();
    }

    // ====================================================================
    // Happy path: mixed states
    // ====================================================================

    @Test
    void mixedStateBatch_terminalImportsGoneInFlightPreservedExportsUntouched() {
        // Two terminal data-import jobs we expect to vanish:
        seedJob("j-os-done", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedLogEntries("j-os-done", 3);
        seedJob("j-s3-failed", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_FAILED);
        seedLogEntries("j-s3-failed", 2);

        // Two in-flight data-import jobs that must survive:
        seedJob("j-es-running", BackgroundJob.TYPE_ELASTICSEARCH_INGEST, BackgroundJob.STATUS_RUNNING);
        seedLogEntries("j-es-running", 5);
        seedJob("j-local-pending", BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST, BackgroundJob.STATUS_PENDING);
        seedLogEntries("j-local-pending", 1);

        // One non-data-import job — even if it's COMPLETED, it is not in the
        // type filter and must not be touched. (BATCH_EXPORT lives on the same
        // collection but has its own admin flow.)
        seedJob("j-export-done", BackgroundJob.TYPE_BATCH_EXPORT, BackgroundJob.STATUS_COMPLETED);

        final RedirectAttributes attrs = flash();
        final String view = controller.cleanupDataImports(attrs);

        assertEquals("redirect:/admin/tools", view);

        // Jobs: terminal imports gone, in-flight imports and the export remain.
        assertFalse(jobs.containsKey("j-os-done"));
        assertFalse(jobs.containsKey("j-s3-failed"));
        assertTrue(jobs.containsKey("j-es-running"));
        assertTrue(jobs.containsKey("j-local-pending"));
        assertTrue(jobs.containsKey("j-export-done"));

        // Log entries: only the two terminal jobs' logs are removed, totalling
        // 3 + 2 = 5 rows. Logs for the running and pending jobs are preserved.
        assertEquals(5 + 1, entries.values().stream()
                .filter(e -> e.getJobId().equals("j-es-running") || e.getJobId().equals("j-local-pending"))
                .count());
        assertFalse(entries.values().stream().anyMatch(e -> e.getJobId().equals("j-os-done")));
        assertFalse(entries.values().stream().anyMatch(e -> e.getJobId().equals("j-s3-failed")));

        // Flash summary reports the counts and reassures the operator that pending/running
        // jobs were spared.
        final String msg = (String) attrs.getFlashAttributes().get("success");
        assertNotNull(msg);
        assertTrue(msg.contains("2"), msg);  // 2 jobs removed
        assertTrue(msg.contains("5"), msg);  // 5 log entries removed
        assertTrue(msg.toLowerCase().contains("pending"), msg);
        assertTrue(msg.toLowerCase().contains("running"), msg);

        // Audit log gets the counts and the statuses-removed set so post-hoc
        // forensics can tell exactly what the tool considered.
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> auditCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("ADMIN_TOOLS_CLEANUP_DATA_IMPORTS"),
                eq("Tools"), eq("data-imports"), auditCaptor.capture());
        final Map<String, Object> audit = auditCaptor.getValue();
        assertEquals(2L, audit.get("jobsDeleted"));
        assertEquals(5L, audit.get("logEntriesDeleted"));
        @SuppressWarnings("unchecked")
        final List<String> statuses = (List<String>) audit.get("statusesRemoved");
        assertTrue(statuses.contains(BackgroundJob.STATUS_COMPLETED));
        assertTrue(statuses.contains(BackgroundJob.STATUS_FAILED));
        assertFalse(statuses.contains(BackgroundJob.STATUS_PENDING));
        assertFalse(statuses.contains(BackgroundJob.STATUS_RUNNING));
    }

    // ====================================================================
    // Edge / negative cases
    // ====================================================================

    @Test
    void emptyDatabaseStillReturnsSuccessAndZeroCounts() {
        // No jobs, no log entries — cleanup must not throw and must still flash a
        // success message so the admin knows the click took effect. Total counts
        // are 0/0.
        final RedirectAttributes attrs = flash();
        controller.cleanupDataImports(attrs);

        final String msg = (String) attrs.getFlashAttributes().get("success");
        assertNotNull(msg);
        assertTrue(msg.contains("0"), msg);
    }

    @Test
    void onlyInFlightJobsLeavesEverythingUntouched() {
        // The set of terminal jobs may be empty — common when an admin clicks the
        // tool while imports are in progress. The flow must not delete the
        // RUNNING / PENDING rows or any log entries belonging to them.
        seedJob("j-running", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_RUNNING);
        seedLogEntries("j-running", 4);
        seedJob("j-pending", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_PENDING);
        seedLogEntries("j-pending", 1);

        final RedirectAttributes attrs = flash();
        controller.cleanupDataImports(attrs);

        // Both jobs survive.
        assertTrue(jobs.containsKey("j-running"));
        assertTrue(jobs.containsKey("j-pending"));
        // All 5 log entries survive.
        assertEquals(5, entries.size());
    }

    @Test
    void cleanupIsIdempotentSecondInvocationIsNoOp() {
        // Running the tool twice in a row must not double-count or delete anything
        // it missed the first time. After the first run, the in-flight jobs that
        // remain should still remain.
        seedJob("j-done", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedLogEntries("j-done", 3);
        seedJob("j-running", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_RUNNING);
        seedLogEntries("j-running", 2);

        controller.cleanupDataImports(flash());
        // First pass should have removed the COMPLETED row and its 3 log entries.
        assertFalse(jobs.containsKey("j-done"));
        assertEquals(2, entries.size());

        final RedirectAttributes attrs2 = flash();
        controller.cleanupDataImports(attrs2);

        // Second pass: no further changes.
        assertTrue(jobs.containsKey("j-running"));
        assertEquals(2, entries.size());

        // The second-pass success message still rendered with 0/0 counts — confirms
        // the tool reports gracefully on a no-op cleanup.
        final String msg = (String) attrs2.getFlashAttributes().get("success");
        assertNotNull(msg);
        assertTrue(msg.contains("0"), msg);
    }

    @Test
    void jobWithBlankIdIsFilteredFromLogDelete() {
        // A blank/null job id could only happen via direct Mongo corruption, but the
        // controller filters them when collecting ids so the downstream
        // deleteByJobIdIn call sees only well-formed ids. The job-row delete still
        // succeeds via type+status (not id-based), so the corrupt row is gone too.
        seedJob("", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED);
        // A real terminal job alongside so we can verify the log-delete still ran
        // with the real id.
        seedJob("j-real", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedLogEntries("j-real", 2);

        controller.cleanupDataImports(flash());

        assertEquals(0, jobs.size());
        assertEquals(0, entries.size());
        // Verify the captured ids set contains exactly the real id — no empty string.
        @SuppressWarnings({"unchecked", "rawtypes"})
        final ArgumentCaptor<Collection<String>> idsCaptor =
                ArgumentCaptor.forClass((Class) Collection.class);
        verify(entryRepository).deleteByJobIdIn(idsCaptor.capture());
        assertFalse(idsCaptor.getValue().contains(""));
        assertTrue(idsCaptor.getValue().contains("j-real"));
    }

    @Test
    void cleanupCoversEveryDataImportType() {
        // One terminal job per data-import type, plus one log entry on each, so a
        // regression that drops a type from the filter set causes a visible orphan.
        seedJob("j-os", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedJob("j-es", BackgroundJob.TYPE_ELASTICSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedJob("j-local", BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedJob("j-s3", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedLogEntries("j-os", 1);
        seedLogEntries("j-es", 1);
        seedLogEntries("j-local", 1);
        seedLogEntries("j-s3", 1);

        controller.cleanupDataImports(flash());

        assertEquals(0, jobs.size());
        assertEquals(0, entries.size());
    }

    @Test
    void onlyExportJobsAreLeftAloneEvenWhenCompleted() {
        // The tool's name is "Clean up data import jobs". Export jobs live in the
        // same collection but must never be deleted by this tool — they have their
        // own dedicated admin flow.
        seedJob("j-export-done", BackgroundJob.TYPE_BATCH_EXPORT, BackgroundJob.STATUS_COMPLETED);
        seedJob("j-export-failed", BackgroundJob.TYPE_BATCH_EXPORT, BackgroundJob.STATUS_FAILED);

        controller.cleanupDataImports(flash());

        assertEquals(2, jobs.size());
        assertTrue(jobs.containsKey("j-export-done"));
        assertTrue(jobs.containsKey("j-export-failed"));
    }

    @Test
    void logEntryWhoseJobIsAlreadyMissingIsNotResurrected() {
        // A "dangling" log entry — one whose job id no longer exists — must not
        // cause the cleanup to fail. The id is not in the terminal set, so the
        // entry stays untouched.
        final DataImportLogEntry orphan = new DataImportLogEntry();
        orphan.setId("orphan");
        orphan.setJobId("j-vanished");
        orphan.setOutcome(DataImportLogEntry.OUTCOME_SUCCESS);
        orphan.setTimestamp(Instant.now());
        entries.put(orphan.getId(), orphan);

        seedJob("j-real", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_COMPLETED);
        seedLogEntries("j-real", 1);

        controller.cleanupDataImports(flash());

        // j-real's job row + log went away. The orphan log entry is preserved
        // because its job id wasn't in the terminal set.
        assertEquals(0, jobs.size());
        assertEquals(Set.of("orphan"), entries.keySet());
    }
}
