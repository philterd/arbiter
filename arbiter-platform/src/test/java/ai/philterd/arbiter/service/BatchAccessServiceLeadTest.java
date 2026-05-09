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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the team-lead authorization rules for {@link BatchAccessService#canLeadBatch}
 * and {@link BatchAccessService#canLeadGroup}. Lead status is per-group: a user can lead
 * group A and merely be a member of group B; the lead authority does not transfer.
 */
class BatchAccessServiceLeadTest {

    private UserGroupsService userGroupsService;
    private BatchAccessService service;

    @BeforeEach
    void setUp() {
        final BatchRepository batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        service = new BatchAccessService(batchRepository, userGroupsService);
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication admin(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication auditor(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        return b;
    }

    @Test
    void canLeadBatchTrueForAdmin() {
        // Admins always lead. The userGroupsService isn't even consulted.
        assertTrue(service.canLeadBatch(admin("admin@x.com"), batch("b1", "g1")));
    }

    @Test
    void canLeadBatchTrueForLeadOfTheSpecificGroup() {
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        assertTrue(service.canLeadBatch(user("alice@x.com"), batch("b1", "g1")));
    }

    @Test
    void canLeadBatchFalseForMemberWhoIsNotLead() {
        // Per-group: a USER who's a member of g1 but not designated as a lead must
        // not pass canLeadBatch. They can still read the batch via canAccessBatch
        // (group membership is enough for reads).
        when(userGroupsService.leadGroupIdsForEmail("bob@x.com")).thenReturn(Set.of());
        assertFalse(service.canLeadBatch(user("bob@x.com"), batch("b1", "g1")));
    }

    @Test
    void canLeadBatchFalseForLeadOfDifferentGroup() {
        // The whole point of per-group leadership: a lead of g2 cannot lead a batch
        // assigned to g1. This is the central authorization invariant for the role.
        when(userGroupsService.leadGroupIdsForEmail("carol@x.com")).thenReturn(Set.of("g2"));
        assertFalse(service.canLeadBatch(user("carol@x.com"), batch("b1", "g1")));
    }

    @Test
    void canLeadBatchFalseForAuditor() {
        // AUDITOR is read-only. Per-group lead authority does not transfer to the
        // read-only role even if the underlying user happens to also be a lead — the
        // role-based check rejects up front, and there's no group-membership concept
        // for auditors anyway.
        assertFalse(service.canLeadBatch(auditor("audit@x.com"), batch("b1", "g1")));
    }

    @Test
    void canLeadBatchFalseForNullBatch() {
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        assertFalse(service.canLeadBatch(user("alice@x.com"), null));
    }

    @Test
    void canLeadBatchFalseForBatchWithoutGroupId() {
        // A batch missing a groupId can't be matched to any lead — refuse rather than
        // grant accidentally. Admins still pass (they go through the role check first).
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        assertFalse(service.canLeadBatch(user("alice@x.com"), batch("b1", null)));
    }

    @Test
    void canLeadBatchFalseForUnauthenticated() {
        assertFalse(service.canLeadBatch(null, batch("b1", "g1")));
    }

    // ---------- canLeadGroup ----------

    @Test
    void canLeadGroupTrueForAdmin() {
        assertTrue(service.canLeadGroup(admin("admin@x.com"), "any-group"));
    }

    @Test
    void canLeadGroupTrueWhenUserLeadsThatGroup() {
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1", "g3"));
        assertTrue(service.canLeadGroup(user("alice@x.com"), "g1"));
    }

    @Test
    void canLeadGroupFalseWhenUserLeadsADifferentGroup() {
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g3"));
        assertFalse(service.canLeadGroup(user("alice@x.com"), "g1"));
    }

    @Test
    void canLeadGroupFalseForBlankGroupId() {
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));
        assertFalse(service.canLeadGroup(user("alice@x.com"), ""));
        assertFalse(service.canLeadGroup(user("alice@x.com"), null));
    }
}
