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
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchHit;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchResults;
import ai.philterd.arbiter.service.UserGroupsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchController}'s authorization model. Confirms the controller
 * passes the caller's allow-list of batchIds down to the OpenSearch service so the reported
 * {@code total} and the hit list both exclude documents the caller can't see — and that the
 * old "restricted: true" placeholder rows are no longer returned (those leaked the existence
 * of inaccessible documents through their position and count).
 */
class SearchControllerTest {

    private OpenSearchIndexService openSearchIndexService;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private SearchController controller;

    @BeforeEach
    void setUp() {
        openSearchIndexService = mock(OpenSearchIndexService.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new SearchController(openSearchIndexService, batchRepository,
                documentRepository, userGroupsService, auditLogService);

        // Default: no documents in MongoDB lookup (lets each test focus on the search side).
        when(documentRepository.findAllById(any())).thenReturn(List.of());
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName("Batch " + id);
        return b;
    }

    private static SearchHit hit(final String id, final String batchId) {
        return new SearchHit(id, batchId, id + ".txt", "REVIEW_REQUIRED", List.of("snippet"));
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> captureAllowedBatchIds(final OpenSearchIndexService svc) {
        final ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(svc).search(anyString(), anyInt(), anyInt(), captor.capture());
        return captor.getValue();
    }

    @Test
    void adminCallsServiceWithNullAllowList() {
        when(openSearchIndexService.search(eq("ssn"), eq(0), eq(10), isNull()))
                .thenReturn(new SearchResults(0, 0, 10, List.of()));

        controller.search("ssn", 0, 10, admin());

        // Admins pass null so the service runs against the full index.
        verify(openSearchIndexService).search(eq("ssn"), eq(0), eq(10), isNull());
    }

    @Test
    void nonAdminPassesItsAllowedBatchIdsDownToService() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));
        @SuppressWarnings({"rawtypes", "unchecked"})
        final Page page = new PageImpl<>(List.of(
                batch("b-mine", "g-mine"),
                batch("b-theirs", "g-other")), PageRequest.of(0, 500), 2);
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new SearchResults(0, 0, 10, List.of()));

        controller.search("ssn", 0, 10, user("alice@x.com"));

        final Collection<String> allowed = captureAllowedBatchIds(openSearchIndexService);
        assertEquals(Set.of("b-mine"), Set.copyOf(allowed),
                "non-admin should have only their group's batches in the allow-list");
    }

    @Test
    void nonAdminWithNoGroupsGetsEmptyAllowListAndShortCircuits() {
        when(userGroupsService.groupIdsForEmail("bob@x.com")).thenReturn(Set.of());
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(batch("b1", "g1")), PageRequest.of(0, 500), 1));
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new SearchResults(0, 0, 10, List.of()));

        final Map<String, Object> out = controller.search("ssn", 0, 10, user("bob@x.com"));

        // Allow-list is empty — the service is still called but the result is empty.
        final Collection<String> allowed = captureAllowedBatchIds(openSearchIndexService);
        assertTrue(allowed.isEmpty(), "no group memberships → empty allow-list");
        assertEquals(0L, out.get("total"));
        assertTrue(((List<?>) out.get("hits")).isEmpty());
    }

    @Test
    void totalReflectsRestrictedQueryNotFullIndex() {
        // The service returns a total of 3 (all matching docs in batches the user *can* see)
        // — even if the underlying index has many more matches, the user only learns this number.
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(batch("b-mine", "g-mine")), PageRequest.of(0, 500), 1));
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new SearchResults(3, 0, 10, List.of(
                        hit("d1", "b-mine"),
                        hit("d2", "b-mine"),
                        hit("d3", "b-mine"))));

        final Map<String, Object> out = controller.search("ssn", 0, 10, user("alice@x.com"));

        assertEquals(3L, out.get("total"));
        assertEquals(3, ((List<?>) out.get("hits")).size());
    }

    @Test
    void hitsNoLongerCarryRestrictedField() {
        // Defense-in-depth: even if the service somehow returned a hit outside the allow-list,
        // the controller must skip it silently — never expose a "restricted" placeholder that
        // leaks the existence of foreign documents.
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(batch("b-mine", "g-mine")), PageRequest.of(0, 500), 1));
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new SearchResults(2, 0, 10, List.of(
                        hit("d1", "b-mine"),
                        hit("rogue", "b-foreign"))));

        final Map<String, Object> out = controller.search("ssn", 0, 10, user("alice@x.com"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertEquals(1, hits.size(), "rogue hit should have been silently dropped");
        assertEquals("d1", hits.get(0).get("id"));
        // No "restricted" key on any hit.
        for (Map<String, Object> hit : hits) {
            assertFalse(hit.containsKey("restricted"),
                    "the restricted placeholder is removed; only visible hits are returned");
            assertNull(hit.get("restricted"));
        }
    }

    @Test
    void adminSeesEveryHit() {
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), isNull()))
                .thenReturn(new SearchResults(2, 0, 10, List.of(
                        hit("d1", "b-mine"),
                        hit("d2", "b-foreign"))));

        final Map<String, Object> out = controller.search("ssn", 0, 10, admin());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertEquals(2, hits.size(), "admin sees all hits regardless of batch");
        for (Map<String, Object> hit : hits) {
            assertFalse(hit.containsKey("restricted"));
        }
    }

    @Test
    void liveStatusFromMongoOverridesIndexedStatus() {
        // The OpenSearch index status is "REVIEW_REQUIRED" but the live document is APPROVED.
        // The controller should report the live status.
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g-mine"));
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(batch("b-mine", "g-mine")), PageRequest.of(0, 500), 1));
        when(openSearchIndexService.search(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(new SearchResults(1, 0, 10, List.of(hit("d1", "b-mine"))));
        final Document live = new Document();
        live.setId("d1");
        live.setStatus("APPROVED");
        when(documentRepository.findAllById(any())).thenReturn(List.of(live));

        final Map<String, Object> out = controller.search("ssn", 0, 10, user("alice@x.com"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertEquals("APPROVED", hits.get(0).get("status"));
    }
}
