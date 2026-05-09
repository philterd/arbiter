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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Authoritative source for the set of batch IDs a principal may access.
 *
 * <p>Centralises the group-membership filter so every controller uses an identical,
 * paginated query rather than each maintaining its own copy. The scan cap
 * ({@value #BATCH_SCAN_LIMIT}) prevents unbounded {@code findAll()} calls while
 * remaining large enough for realistic deployments.
 */
@Service
public class BatchAccessService {

    public static final int BATCH_SCAN_LIMIT = 500;

    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public BatchAccessService(final BatchRepository batchRepository,
                              final UserGroupsService userGroupsService) {
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    /**
     * Returns the set of batch IDs whose {@code groupId} matches one of the calling
     * user's group memberships. At most {@value #BATCH_SCAN_LIMIT} batches are examined.
     *
     * <p>Returns an empty set when the user has no group memberships or when
     * {@code auth} is {@code null}.
     */
    public Set<String> allowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (myGroupIds.isEmpty()) return Set.of();
        final Set<String> ids = new HashSet<>();
        for (Batch b : batchRepository.findAll(
                PageRequest.of(0, BATCH_SCAN_LIMIT, Sort.by("name"))).getContent()) {
            if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) {
                ids.add(b.getId());
            }
        }
        return ids;
    }
}
