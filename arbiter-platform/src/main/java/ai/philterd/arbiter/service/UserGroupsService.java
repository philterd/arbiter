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
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserGroupsService {

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;

    public UserGroupsService(final UserRepository userRepository, final GroupRepository groupRepository) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
    }

    public Set<String> groupIdsForEmail(final String email) {
        if (email == null || email.isBlank()) return Set.of();
        final User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getId() == null) return Set.of();
        final List<Group> groups = groupRepository.findByUserIdsContaining(user.getId());
        final Set<String> ids = new HashSet<>();
        for (Group g : groups) {
            if (g.getId() != null) ids.add(g.getId());
        }
        return ids;
    }

    /**
     * IDs of groups this user leads. Leadership is per-group: a user can lead group A
     * and merely be a member of group B; this method returns only A. Returns an empty
     * set when the user is not found or leads no group.
     *
     * <p>Leaders are also members (the admin Groups UI enforces leaders ⊆ members),
     * so {@link #groupIdsForEmail(String)} is always a superset of the IDs returned
     * here.
     */
    public Set<String> leadGroupIdsForEmail(final String email) {
        if (email == null || email.isBlank()) return Set.of();
        final User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getId() == null) return Set.of();
        final List<Group> groups = groupRepository.findByLeaderUserIdsContaining(user.getId());
        final Set<String> ids = new HashSet<>();
        for (Group g : groups) {
            if (g.getId() != null) ids.add(g.getId());
        }
        return ids;
    }

}
