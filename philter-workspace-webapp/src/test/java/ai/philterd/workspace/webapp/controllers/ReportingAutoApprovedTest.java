/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.UserGroupsService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Reports page counts a document as auto-approved only by its stored status (issue #41). */
class ReportingAutoApprovedTest {

    @Test
    void onlyDocumentsStoredAutoApprovedAreCounted() {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("b1");
        batch.setDocumentThreshold(0.25);

        // Every document is under the Document Threshold; only one was auto-approved at ingest.
        final List<Document> docs = List.of(
                doc("d1", "REVIEW_REQUIRED"), doc("d2", "AUDIT_REQUIRED"),
                doc("d3", "PENDING"), doc("d4", "AUTO_APPROVED"));

        final BatchRepository batchRepository = mock(BatchRepository.class);
        final DocumentRepository documentRepository = mock(DocumentRepository.class);
        final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(batch)));
        when(documentRepository.findByBatchId("b1")).thenReturn(docs);
        when(auditLogRepository.findByTimestampBetweenAndActionIn(any(), any(), any())).thenReturn(List.of());

        final ReportingController controller = new ReportingController(batchRepository, documentRepository,
                mock(SpanRepository.class), mock(UserGroupsService.class), auditLogRepository);
        final ConcurrentModel model = new ConcurrentModel();
        controller.view(null, null, null, new UsernamePasswordAuthenticationToken("admin@example.com", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), model);

        assertEquals(1L, model.getAttribute("autoApprovedTotal"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("batchRows");
        assertEquals(1L, rows.get(0).get("autoApproved"));
    }

    private static Document doc(final String id, final String status) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId("b1");
        d.setStatus(status);
        d.setRiskScore(0.03);
        return d;
    }
}
