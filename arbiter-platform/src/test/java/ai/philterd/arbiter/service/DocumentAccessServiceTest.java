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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the access-check semantics that controllers rely on. The 404-everywhere policy
 * (uniform "Document not found." / "Span not found." for missing-batch, missing-group, and
 * not-in-group) is what prevents an attacker from probing ids to learn what exists.
 */
class DocumentAccessServiceTest {

    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private DocumentAccessService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        // Default: caller "alice" is in group g-mine; everyone else has no groups.
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));
        when(userGroupsService.groupIdsForEmail("bob@x.com")).thenReturn(Set.of());
        final BatchAccessService batchAccess = new BatchAccessService(batchRepository, userGroupsService);
        service = new DocumentAccessService(batchRepository, documentRepository, batchAccess);
    }

    private static Document doc(final String id, final String batchId) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        return d;
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        return b;
    }

    private static Span span(final String id, final String docId) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        return s;
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication admin(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // ----- requireDocumentAccess -----

    @Test
    void requireDocumentAccessAllowsAdminWithoutBatchLookup() {
        // Admins short-circuit before any repository lookup, so a missing batch is fine.
        service.requireDocumentAccess(admin("admin@x.com"), doc("d1", "missing-batch"));
        // No verify(batchRepository) — admins bypass the lookup, and that fast path is the
        // whole reason we keep an explicit isAdmin short-circuit at this layer.
    }

    @Test
    void requireDocumentAccessAllowsCallerInGroup() {
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", "g-mine")));
        service.requireDocumentAccess(user("alice@x.com"), doc("d1", "b1"));
    }

    @Test
    void requireDocumentAccessThrows404WhenCallerNotInGroup() {
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", "g-foreign")));
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requireDocumentAccess(user("alice@x.com"), doc("d1", "b1")));
        assertEquals(404, e.getStatusCode().value());
        assertTrue(e.getReason().contains("Document not found"));
    }

    @Test
    void requireDocumentAccessThrows404WhenBatchMissing() {
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requireDocumentAccess(user("alice@x.com"), doc("d1", "ghost")));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void requireDocumentAccessThrows404WhenBatchHasNoGroupId() {
        // A groupless batch can never be reached by a non-admin — 404 closes the oracle.
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", null)));
        assertThrows(ResponseStatusException.class,
                () -> service.requireDocumentAccess(user("alice@x.com"), doc("d1", "b1")));
    }

    @Test
    void requireDocumentAccessThrows404WhenDocumentHasNoBatchId() {
        // batchId == null short-circuits to 404; no repository call, no orphan handling.
        assertThrows(ResponseStatusException.class,
                () -> service.requireDocumentAccess(user("alice@x.com"), doc("d1", null)));
    }

    // ----- loadAccessibleParentForSpan -----

    @Test
    void loadAccessibleParentForSpanReturnsDocumentForAdmin() {
        final Document d = doc("d1", "b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        // Admin bypass: the batch never gets looked up, so a stale batchId is harmless.
        assertSame(d, service.loadAccessibleParentForSpan(span("s1", "d1"), admin("admin@x.com")));
    }

    @Test
    void loadAccessibleParentForSpanReturnsDocumentForGroupMember() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1", "b1")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", "g-mine")));
        final Document result = service.loadAccessibleParentForSpan(span("s1", "d1"),
                user("alice@x.com"));
        assertEquals("d1", result.getId());
    }

    @Test
    void loadAccessibleParentForSpanThrowsSpanNotFoundWhenDocumentMissing() {
        // Crucial: missing document surfaces as "Span not found.", not "Document not found.",
        // so the response body shape can't tell a stale span id from a real-but-orphaned one.
        when(documentRepository.findById("d1")).thenReturn(Optional.empty());
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.loadAccessibleParentForSpan(span("s1", "d1"), user("alice@x.com")));
        assertEquals(404, e.getStatusCode().value());
        assertTrue(e.getReason().contains("Span not found"));
    }

    @Test
    void loadAccessibleParentForSpanThrowsSpanNotFoundWhenCallerNotInGroup() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1", "b1")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", "g-foreign")));
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.loadAccessibleParentForSpan(span("s1", "d1"), user("alice@x.com")));
        assertEquals(404, e.getStatusCode().value());
        assertTrue(e.getReason().contains("Span not found"),
                "must report span-level error so callers can't tell missing-doc from no-access: "
                        + e.getReason());
    }

    @Test
    void loadAccessibleParentForSpanThrowsSpanNotFoundWhenBatchMissing() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc("d1", "ghost")));
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.loadAccessibleParentForSpan(span("s1", "d1"), user("alice@x.com")));
        assertEquals(404, e.getStatusCode().value());
        assertTrue(e.getReason().contains("Span not found"));
    }
}
