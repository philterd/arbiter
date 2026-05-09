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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(batchRepository.findByGroupIdIn(anyCollection())).thenReturn(List.of());
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
        // The repository query already filters by group; the service just collects ids.
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        when(batchRepository.findByGroupIdIn(Set.of("g1"))).thenReturn(
                List.of(batch("b1", "g1"), batch("b3", "g1")));

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertEquals(Set.of("b1", "b3"), result);
    }

    @Test
    void skipsBatchesWithNullId() {
        // groupId is filtered server-side; the only client-side guard is against null ids
        // (which the repository wouldn't return in practice, but we defend anyway).
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        when(batchRepository.findByGroupIdIn(Set.of("g1"))).thenReturn(
                List.of(batch(null, "g1"), batch("b2", "g1")));

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertEquals(Set.of("b2"), result);
    }

    @Test
    void returnsEmptyWhenUserHasNoGroups() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of());

        final Set<String> result = service.allowedBatchIds(user("alice@x.com"));

        assertTrue(result.isEmpty());
        // Short-circuit: repository must not be queried when groups are empty.
        verify(batchRepository, never()).findByGroupIdIn(anyCollection());
    }

    @Test
    void returnsEmptyForNullAuthentication() {
        final Set<String> result = service.allowedBatchIds(null);

        assertTrue(result.isEmpty());
        verify(batchRepository, never()).findByGroupIdIn(anyCollection());
    }

    // ----- query shape -----

    @Test
    void queriesByGroupIdInWithUsersGroups() {
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1", "g2"));

        service.allowedBatchIds(user("alice@x.com"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<String>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(batchRepository).findByGroupIdIn(captor.capture());
        assertEquals(Set.of("g1", "g2"), Set.copyOf(captor.getValue()));
    }
}
