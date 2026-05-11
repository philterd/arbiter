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
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataImportLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminToolsControllerTest {

    private BackgroundJobRepository jobRepository;
    private DataImportLogService importLogService;
    private AuditLogService auditLogService;
    private AdminToolsController controller;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        importLogService = mock(DataImportLogService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminToolsController(jobRepository, importLogService, auditLogService);
    }

    private static BackgroundJob job(final String id, final String type, final String status) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setType(type);
        j.setStatus(status);
        return j;
    }

    @Test
    void getRendersAdminToolsView() {
        assertEquals("admin-tools", controller.page(new ConcurrentModel()));
    }

    @Test
    void cleanupDeletesOnlyTerminalJobsAndTheirLogs() {
        final List<BackgroundJob> terminal = List.of(
                job("a", BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.STATUS_COMPLETED),
                job("b", BackgroundJob.TYPE_S3_INGEST, BackgroundJob.STATUS_FAILED));
        when(jobRepository.findByTypeInAndStatusIn(anyCollection(), anyCollection()))
                .thenReturn(terminal);
        when(importLogService.deleteByJobIds(anyCollection())).thenReturn(17L);
        when(jobRepository.deleteByTypeInAndStatusIn(anyCollection(), anyCollection())).thenReturn(2L);

        final RedirectAttributes attrs = new RedirectAttributesModelMap();
        final String view = controller.cleanupDataImports(attrs);

        assertEquals("redirect:/admin/tools", view);

        // The terminal-status filter is the load-bearing safety guarantee: PENDING /
        // RUNNING jobs must not be eligible for deletion. Verify the statuses passed
        // to both the lookup and the delete call.
        final ArgumentCaptor<Collection<String>> typesCaptor = collectionCaptor();
        final ArgumentCaptor<Collection<String>> statusesCaptor = collectionCaptor();
        verify(jobRepository).findByTypeInAndStatusIn(typesCaptor.capture(), statusesCaptor.capture());
        final Collection<String> types = typesCaptor.getValue();
        assertTrue(types.contains(BackgroundJob.TYPE_OPENSEARCH_INGEST));
        assertTrue(types.contains(BackgroundJob.TYPE_ELASTICSEARCH_INGEST));
        assertTrue(types.contains(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST));
        assertTrue(types.contains(BackgroundJob.TYPE_S3_INGEST));
        final Collection<String> statuses = statusesCaptor.getValue();
        assertTrue(statuses.contains(BackgroundJob.STATUS_COMPLETED));
        assertTrue(statuses.contains(BackgroundJob.STATUS_FAILED));
        assertTrue(!statuses.contains(BackgroundJob.STATUS_PENDING));
        assertTrue(!statuses.contains(BackgroundJob.STATUS_RUNNING));

        // Log entries are deleted by the ids of the terminal jobs only — not by
        // wiping the whole collection — so in-flight jobs keep their log rows.
        final ArgumentCaptor<Collection<String>> idsCaptor = collectionCaptor();
        verify(importLogService).deleteByJobIds(idsCaptor.capture());
        assertEquals(Set.of("a", "b"), Set.copyOf(idsCaptor.getValue()));

        verify(jobRepository).deleteByTypeInAndStatusIn(anyCollection(), anyCollection());

        final Object success = attrs.getFlashAttributes().get("success");
        assertNotNull(success);
        final String msg = success.toString();
        assertTrue(msg.contains("2"), msg);
        assertTrue(msg.contains("17"), msg);
        // Reassure the operator that running/pending jobs were spared.
        assertTrue(msg.toLowerCase().contains("pending"), msg);
    }

    @Test
    void cleanupWithNoTerminalJobsStillCompletesAndSkipsLogDelete() {
        when(jobRepository.findByTypeInAndStatusIn(anyCollection(), anyCollection()))
                .thenReturn(List.of());
        when(jobRepository.deleteByTypeInAndStatusIn(anyCollection(), anyCollection())).thenReturn(0L);

        final RedirectAttributes attrs = new RedirectAttributesModelMap();
        controller.cleanupDataImports(attrs);

        // Empty id set short-circuits in the service; we don't issue a Mongo
        // deleteByJobIdIn with an empty filter (which would match everything).
        verify(importLogService).deleteByJobIds(Set.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<String>> collectionCaptor() {
        return ArgumentCaptor.forClass((Class) Collection.class);
    }
}
