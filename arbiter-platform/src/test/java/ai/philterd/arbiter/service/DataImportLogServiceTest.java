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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataImportLogService}. The service is mostly a thin wrapper
 * around the repository but its three concerns are each load-bearing for the
 * Background Jobs UI:
 *
 * <ul>
 *     <li><strong>Persist-failure isolation</strong> — a Mongo glitch on a log write must
 *         not abort the running ingest job. Each {@code success/skipped/failed} call
 *         wraps the save and swallows runtime exceptions.</li>
 *     <li><strong>Pagination clamping</strong> — {@code forJob(jobId, page, size)} clamps
 *         {@code size} to {@code [1, 200]} and {@code page} to non-negative so a malicious
 *         or accidental {@code size=0} doesn't crash the repository or page the entire
 *         collection.</li>
 *     <li><strong>Cleanup correctness</strong> — {@link DataImportLogService#deleteByJobIds}
 *         short-circuits on null/empty input so it never issues a Mongo
 *         {@code deleteByJobIdIn([])} which would (in some drivers) match every row.</li>
 * </ul>
 */
class DataImportLogServiceTest {

    private DataImportLogEntryRepository repository;
    private DataImportLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(DataImportLogEntryRepository.class);
        service = new DataImportLogService(repository);
    }

    // ====================================================================
    // success / skipped / failed
    // ====================================================================

    @Test
    void successPersistsEntryWithOutcomeSuccess() {
        service.success("job-1", "file.txt", "src-1");

        final ArgumentCaptor<DataImportLogEntry> captor = ArgumentCaptor.forClass(DataImportLogEntry.class);
        verify(repository).save(captor.capture());
        final DataImportLogEntry e = captor.getValue();
        assertEquals("job-1", e.getJobId());
        assertEquals("file.txt", e.getFilename());
        assertEquals("src-1", e.getSourceDocId());
        assertEquals(DataImportLogEntry.OUTCOME_SUCCESS, e.getOutcome());
        // Success rows carry no message — the UI only renders a badge.
        assertEquals(null, e.getMessage());
    }

    @Test
    void skippedPersistsEntryWithOutcomeSkipped() {
        service.skipped("job-1", "file.txt", "src-1");

        final ArgumentCaptor<DataImportLogEntry> captor = ArgumentCaptor.forClass(DataImportLogEntry.class);
        verify(repository).save(captor.capture());
        assertEquals(DataImportLogEntry.OUTCOME_SKIPPED, captor.getValue().getOutcome());
    }

    @Test
    void failedPersistsEntryWithOutcomeFailedAndReasonAsMessage() {
        service.failed("job-1", "file.txt", "src-1", "S3 returned 403");

        final ArgumentCaptor<DataImportLogEntry> captor = ArgumentCaptor.forClass(DataImportLogEntry.class);
        verify(repository).save(captor.capture());
        assertEquals(DataImportLogEntry.OUTCOME_FAILED, captor.getValue().getOutcome());
        assertEquals("S3 returned 403", captor.getValue().getMessage());
    }

    @Test
    void nullFilenameIsCoercedToEmptyStringRatherThanLeftNull() {
        // The UI fields are non-nullable in the badge renderer; coerce here so a
        // missing source-side filename doesn't crash the modal.
        service.success("job-1", null, "src-1");

        final ArgumentCaptor<DataImportLogEntry> captor = ArgumentCaptor.forClass(DataImportLogEntry.class);
        verify(repository).save(captor.capture());
        assertEquals("", captor.getValue().getFilename());
    }

    @Test
    void recordIgnoresNullJobIdToAvoidWritingOrphanRows() {
        service.success(null, "file.txt", "src-1");
        verifyNoInteractions(repository);
    }

    @Test
    void recordIgnoresBlankJobId() {
        service.success("   ", "file.txt", "src-1");
        verifyNoInteractions(repository);
    }

    @Test
    void persistFailureDuringRecordIsSwallowedNotPropagated() {
        // The contract is documented on DataImportLogService: a Mongo glitch on a
        // log write must not abort the running ingest job, so the runtime is caught.
        when(repository.save(any(DataImportLogEntry.class)))
                .thenThrow(new RuntimeException("Mongo unreachable"));

        // No exception is thrown back to the caller.
        service.success("job-1", "file.txt", "src-1");
        service.skipped("job-1", "file.txt", "src-1");
        service.failed("job-1", "file.txt", "src-1", "irrelevant");

        verify(repository, times(3)).save(any(DataImportLogEntry.class));
    }

    // ====================================================================
    // forJob (paged)
    // ====================================================================

    @Test
    void pagedForJobUsesRequestedPageAndSize() {
        final Page<DataImportLogEntry> stub = new PageImpl<>(List.of());
        when(repository.findByJobIdOrderByTimestampAsc(eq("j"), any(Pageable.class)))
                .thenReturn(stub);

        service.forJob("j", 2, 10);

        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByJobIdOrderByTimestampAsc(eq("j"), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    void pagedForJobClampsNegativePageToZero() {
        // PageRequest itself rejects negative page numbers with an IllegalArgumentException;
        // the service is responsible for sanitising the caller's input first.
        final Page<DataImportLogEntry> stub = new PageImpl<>(List.of());
        when(repository.findByJobIdOrderByTimestampAsc(anyString(), any(Pageable.class)))
                .thenReturn(stub);

        service.forJob("j", -5, 10);

        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByJobIdOrderByTimestampAsc(anyString(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    void pagedForJobClampsZeroSizeToOne() {
        // PageRequest.of(..., 0) throws; protect the repository from a caller passing 0.
        final Page<DataImportLogEntry> stub = new PageImpl<>(List.of());
        when(repository.findByJobIdOrderByTimestampAsc(anyString(), any(Pageable.class)))
                .thenReturn(stub);

        service.forJob("j", 0, 0);

        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByJobIdOrderByTimestampAsc(anyString(), captor.capture());
        assertEquals(1, captor.getValue().getPageSize());
    }

    @Test
    void pagedForJobClampsExcessiveSizeTo200() {
        // Don't let the UI ask for the whole collection in one shot. 200 is the cap.
        final Page<DataImportLogEntry> stub = new PageImpl<>(List.of());
        when(repository.findByJobIdOrderByTimestampAsc(anyString(), any(Pageable.class)))
                .thenReturn(stub);

        service.forJob("j", 0, 10_000);

        final ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByJobIdOrderByTimestampAsc(anyString(), captor.capture());
        assertEquals(200, captor.getValue().getPageSize());
    }

    @Test
    void pagedForJobReturnsRepositoryPageVerbatim() {
        final Page<DataImportLogEntry> stub = new PageImpl<>(List.of());
        when(repository.findByJobIdOrderByTimestampAsc(eq("j"), any(Pageable.class)))
                .thenReturn(stub);

        assertSame(stub, service.forJob("j", 0, 10));
    }

    // ====================================================================
    // forJob (full list)
    // ====================================================================

    @Test
    void unpagedForJobReturnsRepositoryListVerbatim() {
        final DataImportLogEntry e1 = new DataImportLogEntry();
        final DataImportLogEntry e2 = new DataImportLogEntry();
        when(repository.findByJobIdOrderByTimestampAsc("j")).thenReturn(List.of(e1, e2));

        final List<DataImportLogEntry> got = service.forJob("j");

        assertEquals(2, got.size());
        assertSame(e1, got.get(0));
        assertSame(e2, got.get(1));
    }

    // ====================================================================
    // deleteByJobIds
    // ====================================================================

    @Test
    void deleteByJobIdsForwardsToRepositoryAndReturnsCount() {
        when(repository.deleteByJobIdIn(Set.of("a", "b"))).thenReturn(7L);

        assertEquals(7L, service.deleteByJobIds(Set.of("a", "b")));
        verify(repository).deleteByJobIdIn(Set.of("a", "b"));
    }

    @Test
    void deleteByJobIdsShortCircuitsOnNullCollection() {
        // A null collection must not call the repository — Mongo "in []" semantics
        // in some drivers behave like "match everything", which would wipe the log
        // store. Returning 0L with no call is the safe failure mode.
        assertEquals(0L, service.deleteByJobIds(null));
        verify(repository, never()).deleteByJobIdIn(any());
    }

    @Test
    void deleteByJobIdsShortCircuitsOnEmptyCollection() {
        // Same guard as the null case — covered separately so a regression in either
        // branch surfaces a distinct failure.
        assertEquals(0L, service.deleteByJobIds(Collections.emptySet()));
        verify(repository, never()).deleteByJobIdIn(any());
    }

    @Test
    void deleteByJobIdsPassesSingletonThrough() {
        // Single-id case is a useful smoke test: a singleton collection must not be
        // confused with the "empty" short-circuit branch above.
        when(repository.deleteByJobIdIn(any(Collection.class))).thenReturn(1L);

        assertEquals(1L, service.deleteByJobIds(Set.of("only-job")));

        @SuppressWarnings({"unchecked", "rawtypes"})
        final ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass((Class) Collection.class);
        verify(repository).deleteByJobIdIn(captor.capture());
        assertTrue(captor.getValue().contains("only-job"));
        assertEquals(1, captor.getValue().size());
    }
}
