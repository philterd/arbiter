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
     * or hold a group membership matching {@link Batch#getGroupId()}. A {@code null} batch
     * or a batch without a group id is treated as inaccessible to non-admins.
     */
    public boolean canAccessBatch(final Authentication auth, final Batch batch) {
        if (AuthUtils.isAdmin(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        return myGroupIds.contains(batch.getGroupId());
    }
}
