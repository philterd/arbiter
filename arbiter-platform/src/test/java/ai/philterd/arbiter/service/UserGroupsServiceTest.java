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

import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserGroupsServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final UserGroupsService service = new UserGroupsService(users, groups);

    private static User user(final String id, final String email) {
        final User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private static Group group(final String id, final String name) {
        final Group g = new Group();
        g.setId(id);
        g.setName(name);
        return g;
    }

    @Test
    void blankEmailReturnsEmpty() {
        assertTrue(service.groupIdsForEmail(null).isEmpty());
        assertTrue(service.groupIdsForEmail("").isEmpty());
        assertTrue(service.groupIdsForEmail("   ").isEmpty());
    }

    @Test
    void unknownUserReturnsEmpty() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        assertTrue(service.groupIdsForEmail("nobody@example.com").isEmpty());
    }

    @Test
    void userWithNoGroupsReturnsEmpty() {
        final User u = user("u1", "alice@example.com");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of());
        assertTrue(service.groupIdsForEmail("alice@example.com").isEmpty());
    }

    @Test
    void returnsAllGroupIdsForUser() {
        final User u = user("u1", "alice@example.com");
        final Group g1 = group("g1", "Reviewers");
        final Group g2 = group("g2", "Auditors");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of(g1, g2));

        final Set<String> ids = service.groupIdsForEmail("alice@example.com");
        assertEquals(Set.of("g1", "g2"), ids);
    }

    @Test
    void groupsWithNullIdAreSkipped() {
        final User u = user("u1", "alice@example.com");
        final Group good = group("g1", "Reviewers");
        final Group ghost = group(null, "Ghost");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of(good, ghost));

        final Set<String> ids = service.groupIdsForEmail("alice@example.com");
        assertEquals(Set.of("g1"), ids);
    }
}
