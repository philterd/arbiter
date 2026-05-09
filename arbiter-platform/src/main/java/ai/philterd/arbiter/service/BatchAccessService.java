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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Authoritative source for the set of batch IDs a principal may access.
 *
 * <p>Centralises the group-membership filter so every controller uses an identical query
 * rather than each maintaining its own copy. Result size is bounded by the caller's group
 * memberships (typically a handful of groups), so the query touches only the matching
 * rows — no scan cap needed.
 */
@Service
public class BatchAccessService {

    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public BatchAccessService(final BatchRepository batchRepository,
                              final UserGroupsService userGroupsService) {
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    /**
     * Returns the set of batch IDs whose {@code groupId} matches one of the calling
     * user's group memberships.
     *
     * <p>Returns an empty set when the user has no group memberships or when
     * {@code auth} is {@code null}.
     */
    public Set<String> allowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (myGroupIds.isEmpty()) return Set.of();
        final Set<String> ids = new HashSet<>();
        for (Batch b : batchRepository.findByGroupIdIn(myGroupIds)) {
            if (b.getId() != null) ids.add(b.getId());
        }
        return ids;
    }

    /**
     * Returns {@code true} if the caller may access {@code batch} — i.e. they are an admin
     * or auditor (both see all batches), or hold a group membership matching
     * {@link Batch#getGroupId()}. A {@code null} batch or a batch without a group id is
     * treated as inaccessible to non-admins.
     *
     * <p>Auditors are admitted alongside admins because they are the global read role.
     * The write-side gating that prevents auditors from mutating any batch lives in the
     * {@code AuditorWriteRejectFilter} on the security pipeline, so it is safe to widen
     * read access here without scattering write checks across every controller.
     */
    public boolean canAccessBatch(final Authentication auth, final Batch batch) {
        if (AuthUtils.isAdminOrAuditor(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        return myGroupIds.contains(batch.getGroupId());
    }

    /**
     * Returns {@code true} when the caller may perform admin-level mutations on
     * {@code batch} — i.e. they are an admin (global authority) or a designated
     * <em>team lead</em> of the batch's group. Auditors are explicitly excluded:
     * the role is read-only and {@link AuthUtils#isAdminOrAuditor(Authentication)} is
     * not the right check here.
     *
     * <p>Team leadership is per-group: the same user can lead group A and merely
     * be a member of group B. Use this method to gate batch-level write actions
     * (create / edit / close, weight overrides, threshold changes); use
     * {@link #canAccessBatch(Authentication, Batch)} for read decisions.
     */
    public boolean canLeadBatch(final Authentication auth, final Batch batch) {
        if (AuthUtils.isAdmin(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        final Set<String> myLedGroupIds = userGroupsService.leadGroupIdsForEmail(
                auth == null ? null : auth.getName());
        return myLedGroupIds.contains(batch.getGroupId());
    }

    /**
     * Returns {@code true} when the caller leads the named group. Used to gate
     * "create a batch in this group" before a Batch object exists. Admins are
     * implicitly authorized for every group.
     */
    public boolean canLeadGroup(final Authentication auth, final String groupId) {
        if (AuthUtils.isAdmin(auth)) return true;
        if (groupId == null || groupId.isBlank()) return false;
        final Set<String> myLedGroupIds = userGroupsService.leadGroupIdsForEmail(
                auth == null ? null : auth.getName());
        return myLedGroupIds.contains(groupId);
    }
}
