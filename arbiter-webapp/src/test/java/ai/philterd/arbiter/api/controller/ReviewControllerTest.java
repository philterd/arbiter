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

import ai.philterd.arbiter.dto.SpanUpdateRequest;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TestDoubles.RecordingAuditLog auditLogService;
    private ReviewController controller;
    private static final org.springframework.security.core.Authentication ADMIN = TestAuth.admin("admin@example.com");

    @BeforeEach
    void setUp() {
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        controller = new ReviewController(spanRepository, documentRepository,
                batchRepository, userGroupsService, auditLogService);
        // Default stub: a document "d1" the access helper can resolve.
        // Tests that need a different shape override this.
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", "the original text")));
    }

    private static Span span(final String id, final String docId, final String type, final String status,
                             final int start, final int end, final String text) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        s.setType(type);
        s.setStatus(status);
        s.setText(text);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    private static Document document(final String id, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setOriginalText(text);
        return d;
    }

    // ---- getSpans ----

    @Test
    void getSpansReturnsRepositoryResult() {
        final Span s = span("s1", "d1", "ssn", "PENDING", 0, 11, "123-45-6789");
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s));

        final List<Span> result = controller.getSpans("d1", ADMIN);

        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).getId());
    }

    @Test
    void getSpansReturns409WhenSourceCleared() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", null)));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalization policy"));
        verify(spanRepository, never()).findByDocumentId(anyString());
    }

    @Test
    void getSpansReturns409WhenSourceEmpty() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", "")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ---- updateSpan ----

    @Test
    void updateSpanRejectsNullRequest() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", null, ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsBothFieldsNull() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("ghost", new SpanUpdateRequest("APPROVED", null), ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidStatus() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("WHATEVER", null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidType() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, "made-up"), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanAppliesStatusAndAudits() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), ADMIN);

        assertEquals("APPROVED", updated.getStatus());
        assertTrue(auditLogService.hasAction("SPAN_UPDATE"));
    }

    @Test
    void updateSpanNormalizesType() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest(null, "  Phone-Number  "), ADMIN);

        assertEquals("phone-number", updated.getType());
    }

    @Test
    void updateSpanCanChangeBoth() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", "phone-number"), ADMIN);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("phone-number", updated.getType());
    }

    // ---- redactAllLike ----

    @Test
    void redactAllLikeRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void redactAllLikeRejectsRejectedSourceSpan() {
        final Span source = span("s1", "d1", "ssn", "REJECTED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).saveAll(anyList());
    }

    @Test
    void redactAllLikeRejectsSecondOpinionSourceSpan() {
        final Span source = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).saveAll(anyList());
    }

    @Test
    void redactAllLikeRejectsEmptySourceText() {
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 0, "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void redactAllLikeReturnsZerosWhenDocumentTextEmpty() {
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        final Document doc = document("d1", "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
    }

    @Test
    void redactAllLikeCreatesNewSpansForAdditionalMatches() {
        // Document has "Project Icarus" three times. Source span covers the first occurrence.
        final String text = "Project Icarus is great. Look at Project Icarus again. And Project Icarus.";
        final int firstStart = text.indexOf("Project Icarus");
        final int firstEnd = firstStart + "Project Icarus".length();
        final Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "Project Icarus");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // Source becomes APPROVED + 2 new spans created for the other matches.
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository).saveAll(anyList());
        assertTrue(auditLogService.hasAction("SPAN_REDACT_LIKE"));
    }

    @Test
    void redactAllLikeFlipsExistingExactRangesToApproved() {
        // Document has "alpha" twice. Source covers the first; an existing span at the second range exists.
        final String text = "alpha and alpha";
        final int firstStart = 0;
        final int firstEnd = 5;
        final int secondStart = text.indexOf("alpha", firstEnd);
        final int secondEnd = secondStart + 5;

        final Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "alpha");
        final Span existing = span("s2", "d1", "first-name", "PENDING", secondStart, secondEnd, "alpha");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source, existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(1, result.get("approved"));
        // The existing span should now be APPROVED with type aligned to source.
        assertEquals("APPROVED", existing.getStatus());
        assertEquals("person", existing.getType());
    }

    @Test
    void redactAllLikeSkipsOverlappingNonExactRanges() {
        // Source span "abc" appears once; another match "abc" overlaps with an
        // existing-but-non-exact span occupying [5, 10] inside that area.
        final String text = "abc xx abcxxx";
        final Span source = span("s1", "d1", "person", "PENDING", 0, 3, "abc");
        // Existing span covers offsets 5..10 (overlapping the second "abc" at 7..10)
        final Span overlapping = span("s2", "d1", "other", "PENDING", 5, 10, "x abcx");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new ArrayList<>(List.of(source, overlapping)));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // The second "abc" overlaps an existing range, so neither new span nor flip.
        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository, never()).saveAll(anyList());
    }

    // ---- group-access enforcement (non-admin) ----

    /**
     * Wires up document "d1" → batch "b1" → group "g1" so the access helper has
     * something to compare against. The non-admin user is created by the caller.
     */
    private void seedGroupedDocument() {
        final Document doc = document("d1", "the original text");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
    }

    @Test
    void getSpansForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void accessDeniedReturnsSameGenericBodyAsLookupMiss() {
        // The reason field on a 404 must not leak whether the id exists. An attacker
        // probing for valid ids should see an identical message whether they pass an
        // id that doesn't exist or one that exists but they can't see.
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        // Path A: id exists, but caller has no access.
        final ResponseStatusException accessDenied = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));

        // Path B: id genuinely doesn't exist.
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("ghost", TestAuth.user("alice@example.com")));

        assertEquals(HttpStatus.NOT_FOUND, accessDenied.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        // Bodies must be byte-identical — that's the whole point of the standardization.
        assertEquals(missing.getReason(), accessDenied.getReason());
        // And neither must contain a real id.
        assertTrue(accessDenied.getReason() == null
                || !accessDenied.getReason().contains("d1"),
                "access-denied body leaked the id: " + accessDenied.getReason());
        assertTrue(missing.getReason() == null
                || !missing.getReason().contains("ghost"),
                "lookup-miss body leaked the id: " + missing.getReason());
    }

    @Test
    void getSpansAllowsNonAdminInGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));
        when(spanRepository.findByDocumentId("d1"))
                .thenReturn(List.of(span("s1", "d1", "ssn", "PENDING", 0, 5, "hello")));

        final List<Span> result = controller.getSpans("d1", TestAuth.user("alice@example.com"));
        assertEquals(1, result.size());
    }

    @Test
    void updateSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateSpanAccessDeniedReturnsSameGenericBodyAsLookupMiss() {
        // F4 mirror of the F9 indistinguishability test: the body for "no such span" must
        // be identical to "span exists but you can't see it" so an attacker can't enumerate
        // span ids by reading the response message.
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        // Path A: span id exists, but the caller can't see its document.
        final ResponseStatusException accessDenied = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));

        // Path B: span id genuinely doesn't exist.
        when(spanRepository.findById("ghost-span")).thenReturn(Optional.empty());
        final ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("ghost-span", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));

        assertEquals(HttpStatus.NOT_FOUND, accessDenied.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        // Bodies must be byte-identical.
        assertEquals(missing.getReason(), accessDenied.getReason());
        // No id of either span surfaces in the body.
        assertTrue(accessDenied.getReason() == null
                || !accessDenied.getReason().contains("s1"),
                "access-denied body leaked the span id: " + accessDenied.getReason());
        assertTrue(missing.getReason() == null
                || !missing.getReason().contains("ghost"),
                "lookup-miss body leaked the span id: " + missing.getReason());
        // Specifically the body must not say "Document not found." — that would distinguish
        // "span exists, doc inaccessible" from "no such span".
        assertTrue(accessDenied.getReason() != null
                && !accessDenied.getReason().contains("Document"),
                "access-denied body must not mention 'Document': " + accessDenied.getReason());
    }

    @Test
    void updateSpanWhenParentDocumentMissingAlsoReturnsSameBody() {
        // The third failure mode handled by loadAccessibleParentForSpan: the span exists,
        // but its referenced parent document has been deleted. Must surface as the same
        // "Span not found." body — not the previous "Document not found." which would
        // leak the existence of the span row.
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));
        final Span existing = span("s1", "ghost-doc", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(documentRepository.findById("ghost-doc")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Span not found.", ex.getReason());
    }

    @Test
    void redactAllLikeForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void createSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSpan("d1",
                        new ReviewController.CreateSpanRequest("ssn", 0, 5),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- deleteSpan ----

    @Test
    void deleteSpanRejectsUnknown() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteSpanRejectsDetectionSpan() {
        final Span detected = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        // manuallyCreated defaults to false → detection-style span
        when(spanRepository.findById("s1")).thenReturn(Optional.of(detected));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void deleteSpanRemovesManualSpan() {
        final Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        final Map<String, Object> result = controller.deleteSpan("s1", ADMIN);
        assertEquals("s1", result.get("id"));
        assertEquals(true, result.get("deleted"));
        verify(spanRepository).deleteById("s1");
        assertTrue(auditLogService.hasAction("SPAN_DELETE"));
    }

    @Test
    void deleteSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void createSpanMarksManuallyCreated() {
        final Document doc = document("d1", "Hello world, this is a sample document.");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span result = controller.createSpan("d1",
                new ReviewController.CreateSpanRequest("ssn", 0, 5),
                ADMIN);
        assertTrue(result.isManuallyCreated());
        assertEquals(1.0, result.getConfidence());
        assertEquals("APPROVED", result.getStatus());
    }

    // ---- overturn-reason enforcement ----

    private static final org.springframework.security.core.Authentication ALICE =
            TestAuth.admin("alice@example.com");
    private static final org.springframework.security.core.Authentication BOB =
            TestAuth.admin("bob@example.com");

    private static Span approvedBy(final String id, final String docId, final String approver) {
        final Span s = span(id, docId, "ssn", "APPROVED", 0, 5, "hello");
        s.setStatusChangedBy(approver);
        return s;
    }

    private TestDoubles.RecordingAuditLog.Entry lastSpanUpdateAudit() {
        for (int i = auditLogService.entries.size() - 1; i >= 0; i--) {
            final TestDoubles.RecordingAuditLog.Entry e = auditLogService.entries.get(i);
            if ("SPAN_UPDATE".equals(e.action())) return e;
        }
        return null;
    }

    @Test
    void sameReviewerCanFlipTheirOwnApprovedSpanWithoutReason() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("alice@example.com", updated.getStatusChangedBy());
        assertEquals(false, lastSpanUpdateAudit().details().getOrDefault("overturn", false));
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithoutReasonReturns409() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("REJECTED", null, null), BOB));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("OVERTURN_REASON_REQUIRED"));
        verify(spanRepository, never()).save(any(Span.class));
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithBlankReasonReturns409() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("REJECTED", null, "   "), BOB));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithReasonSucceedsAndAuditsReason() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null, "PII not actually present"), BOB);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
        final Map<String, Object> details = lastSpanUpdateAudit().details();
        assertEquals(true, details.get("overturn"));
        assertEquals("PII not actually present", details.get("reason"));
        assertEquals("alice@example.com", details.get("previousStatusChangedBy"));
    }

    @Test
    void overturnIsCaseInsensitiveForReviewerEmail() {
        final Span existing = approvedBy("s1", "d1", "Alice@Example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same human, different casing of email — should NOT trigger an overturn.
        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals(false, lastSpanUpdateAudit().details().getOrDefault("overturn", false));
    }

    @Test
    void noReasonRequiredWhenPriorActorWasSystem() {
        // System-set APPROVED (e.g. auto-approved during ingest) leaves statusChangedBy null.
        // A reviewer can flip it without a reason — no human decision is being overturned.
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        // statusChangedBy intentionally left null
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    @Test
    void noReasonRequiredWhenPriorStatusWasNotApproved() {
        // Bob flips a PENDING span (originally touched by Alice somehow, e.g. type change)
        // to REJECTED — that's not an overturn of an approval.
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void typeOnlyChangeOnApprovedSpanByDifferentReviewerNeedsNoReason() {
        // Status doesn't change — no overturn check applies.
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest(null, "phone-number"), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("phone-number", updated.getType());
    }

    @Test
    void approvedReapprovedByDifferentReviewerNeedsNoReason() {
        // Setting APPROVED again on an already-APPROVED span isn't an overturn.
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    // ---- second-opinion enforcement ----

    @Test
    void requesterCanResolveTheirOwnSecondOpinionToApproved() {
        // Any reviewer — including the requester — may flip a NEEDS_SECOND_OPINION span
        // to APPROVED or REJECTED. The "another reviewer must resolve" constraint is no
        // longer enforced at the span level.
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), ALICE);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("alice@example.com", updated.getStatusChangedBy());
    }

    @Test
    void requesterCanResolveTheirOwnSecondOpinionToRejected() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void differentReviewerCanResolveSecondOpinionToApproved() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    @Test
    void differentReviewerCanResolveSecondOpinionToRejected() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void requesterCanCancelTheirOwnSecondOpinionByMovingToPending() {
        // Self-resolve guard only applies to APPROVED/REJECTED; the requester can return
        // the span to PENDING (effectively cancelling their own request).
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("PENDING", null), ALICE);

        assertEquals("PENDING", updated.getStatus());
    }

    @Test
    void rejectedDocumentBlocksSpanEdits() {
        final Document rejected = document("d1", "");
        rejected.changeStatus("REJECTED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(rejected));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("APPROVED", null), ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("REJECTED"));
    }

    @Test
    void documentEndpointsForbidWhenBatchHasNoGroup() {
        // Document exists but its batch has no groupId — non-admins are blocked.
        final Document doc = document("d1", "");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        // groupId left null
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- resetSpan ----

    @Test
    void resetSpanRejectsArbitraryStatus() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest("HACKED"), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanRejectsNullStatus() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest(null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanRejectsNullRequest() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1", null, ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanAcceptsAllValidStatuses() {
        for (final String status : List.of("APPROVED", "REJECTED", "PENDING", Span.STATUS_NEEDS_SECOND_OPINION)) {
            final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
            when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
            when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

            final Span result = controller.resetSpan("s1",
                    new ReviewController.ResetSpanRequest(status), ADMIN);
            assertEquals(status, result.getStatus(), "Expected status " + status + " to be accepted");
        }
    }

    @Test
    void resetSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest("PENDING"),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }
}
