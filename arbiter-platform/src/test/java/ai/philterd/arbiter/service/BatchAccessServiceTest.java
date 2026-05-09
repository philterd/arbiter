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
import ai.philterd.arbiter.repository.BatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchAccessServiceTest {

    private BatchRepository batchRepository;
    private UserGroupsService userGroupsService;
    private BatchAccessService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        service = new BatchAccessService(batchRepository, userGroupsService);
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        return b;
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ----- group filtering -----

    @Test
    void returnsOnlyBatchesInUsersGroups() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(
                List.of(batch("b1", "g1"), batch("b2", "g2"), batch("b3", "g1"))));

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertEquals(Set.of("b1", "b3"), result);
    }

    @Test
    void excludesBatchesWithNullGroupId() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(
                List.of(batch("b1", null), batch("b2", "g1"))));

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertEquals(Set.of("b2"), result);
    }

    @Test
    void returnsEmptyWhenUserHasNoGroups() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of());

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertTrue(result.isEmpty());
        // Short-circuit: repository must not be queried when groups are empty.
        verify(batchRepository, org.mockito.Mockito.never()).findAll(any(PageRequest.class));
    }

    @Test
    void returnsEmptyForNullAuthentication() {
        final Set<String> result = service.allowedBatchIds(null);

        assertTrue(result.isEmpty());
        verify(batchRepository, org.mockito.Mockito.never()).findAll(any(PageRequest.class));
    }

    // ----- pagination cap -----

    @Test
    void usesPagedQueryWithScanLimit() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        service.allowedBatchIds(user("alice@x.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(batchRepository).findAll(captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(BatchAccessService.BATCH_SCAN_LIMIT, captor.getValue().getPageSize());
    }

    @Test
    void scanLimitIsConsistentAcrossControllers() {
        // Regression guard: SearchController previously used 500; TriageController used
        // findAll() with no limit. Both now delegate here and get the same cap.
        assertEquals(500, BatchAccessService.BATCH_SCAN_LIMIT);
    }
}
