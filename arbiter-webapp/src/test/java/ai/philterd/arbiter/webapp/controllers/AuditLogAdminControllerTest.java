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

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.service.AuditLogQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the new {@code /admin/audit/preview} endpoint that backs the Preview button on the
 * audit-log export form. The preview must use the same query as the export (so the user sees
 * an honest sample of what the download will contain), capped at 10 rows.
 */
class AuditLogAdminControllerTest {

    private AuditLogQueryService auditLogQueryService;
    private AuditLogAdminController controller;

    @BeforeEach
    void setUp() {
        auditLogQueryService = mock(AuditLogQueryService.class);
        controller = new AuditLogAdminController(auditLogQueryService, new ObjectMapper());
    }

    private static AuditLog entry(final String action, final Instant ts) {
        final AuditLog e = new AuditLog();
        e.setAction(action);
        e.setTimestamp(ts);
        e.setOutcome("SUCCESS");
        return e;
    }

    @Test
    void previewDelegatesToQueryServiceWithLimitOf10() {
        final List<AuditLog> stub = List.of(entry("LOGIN", Instant.parse("2026-05-05T10:00:00Z")));
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(stub);

        final List<AuditLog> result = controller.preview(
                "2026-05-05T09:00", "2026-05-05T11:00",
                "alice@example.com", "Document", "doc1");

        assertSame(stub, result);
    }

    @Test
    void previewPassesAllFiltersThrough() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview("2026-05-05T09:00", "2026-05-05T11:00",
                "alice@example.com", "Document", "doc1");

        final ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogQueryService).find(startCap.capture(), endCap.capture(),
                eq("alice@example.com"), eq("Document"), eq("doc1"), eq(10));
        // Times are interpreted in the server's local zone — both ends are populated.
        assertEquals(true, startCap.getValue().isBefore(endCap.getValue()));
    }

    @Test
    void previewWithNoFiltersPassesNullsToQueryService() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview(null, null, null, null, null);

        verify(auditLogQueryService).find(eq(null), eq(null), eq(null), eq(null), eq(null), eq(10));
    }

    @Test
    void previewBlankTimeStringsAreTreatedAsNotPresent() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview("  ", "", "  ", "", "");

        final ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogQueryService).find(startCap.capture(), endCap.capture(),
                any(), any(), any(), eq(10));
        assertNull(startCap.getValue());
        assertNull(endCap.getValue());
    }

    @Test
    void previewRejectsInvertedTimeRange() {
        // start strictly after end → 400 BAD_REQUEST so the user sees a clear error, not an empty preview.
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.preview("2026-05-05T11:00", "2026-05-05T09:00", null, null, null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void previewRejectsUnparseableTimestamp() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.preview("not a date", "2026-05-05T11:00", null, null, null));
        assertEquals(400, ex.getStatusCode().value());
    }
}
