/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.RedactionApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportControllerTest {

    private static final Authentication ADMIN = TestAuth.admin("admin@example.com");

    private RedactionApiService redactionApiService;
    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private ExportController controller;

    @BeforeEach
    void setUp() {
        redactionApiService = mock(RedactionApiService.class);
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        controller = new ExportController(redactionApiService, spanRepository,
                documentRepository, batchRepository, userGroupsService);

        final Document doc = approvedDoc("d1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
    }

    private static Document approvedDoc(final String id) {
        final Document doc = new Document();
        doc.setId(id);
        doc.changeStatus("APPROVED");
        return doc;
    }

    private static Document docWithStatus(final String id, final String status) {
        final Document doc = new Document();
        doc.setId(id);
        if (status != null) doc.changeStatus(status);
        return doc;
    }

    private static Span span(final String id, final String type, final String text, final double conf, final String status) {
        final Span s = new Span();
        s.setId(id);
        s.setType(type);
        s.setText(text);
        s.setConfidence(conf);
        s.setStatus(status);
        s.setLocation(new Location(0, text.length(), 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    @Test
    void finalizeReturnsServiceResult() throws IOException {
        when(redactionApiService.finalizeRedaction("d1")).thenReturn("hello <<SSN>>");

        final Map<String, String> result = controller.finalize("d1", ADMIN);

        assertEquals("hello <<SSN>>", result.get("finalizedText"));
    }

    @Test
    void auditMapsAllSpanFields() {
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(
                span("s1", "ssn", "123-45-6789", 0.95, "APPROVED"),
                span("s2", "phone-number", "555-1234", 0.4, "PENDING")));

        final List<Map<String, Object>> result = controller.audit("d1", ADMIN);

        assertEquals(2, result.size());
        assertEquals("123-45-6789", result.get(0).get("text"));
        assertEquals("ssn", result.get(0).get("type"));
        assertEquals(0.95, result.get(0).get("confidence"));
        assertEquals("APPROVED", result.get(0).get("status"));
        assertEquals("PENDING", result.get(1).get("status"));
    }

    @Test
    void auditReturnsEmptyListWhenNoSpans() {
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());

        final List<Map<String, Object>> result = controller.audit("d1", ADMIN);

        assertTrue(result.isEmpty());
    }

    // ---- group-access enforcement (non-admin) ----

    private void seedGroupedDocument() {
        seedGroupedDocumentWithStatus("APPROVED");
    }

    private void seedGroupedDocumentWithStatus(final String status) {
        final Document doc = docWithStatus("d1", status);
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
    }

    @Test
    void finalizeForbidsNonAdminOutsideGroup() throws IOException {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(redactionApiService, never()).finalizeRedaction(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void auditForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.audit("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).findByDocumentId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void auditAllowsNonAdminInGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());

        final List<Map<String, Object>> result = controller.audit("d1", TestAuth.user("alice@example.com"));
        assertTrue(result.isEmpty());
    }

    // ---- status precondition enforcement ----

    @Test
    void finalizeRejectsPendingDocument() throws IOException {
        seedGroupedDocumentWithStatus("PENDING");
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(redactionApiService, never()).finalizeRedaction(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalizeRejectsRejectedDocument() throws IOException {
        seedGroupedDocumentWithStatus("REJECTED");
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(redactionApiService, never()).finalizeRedaction(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalizeRejectsNullStatus() throws IOException {
        seedGroupedDocumentWithStatus(null);
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(redactionApiService, never()).finalizeRedaction(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void finalizeAllowsApprovedDocument() throws IOException {
        seedGroupedDocument(); // status = APPROVED
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(redactionApiService.finalizeRedaction("d1")).thenReturn("redacted text");

        final Map<String, String> result = controller.finalize("d1", TestAuth.user("alice@example.com"));
        assertEquals("redacted text", result.get("finalizedText"));
    }

    @Test
    void documentEndpointsForbidWhenBatchHasNoGroup() {
        final Document doc = new Document();
        doc.setId("d1");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        // groupId left null
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.audit("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
