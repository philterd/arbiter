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

import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.DocumentAccessService;
import ai.philterd.arbiter.service.UserGroupsService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;

/**
 * Light test doubles for concrete services that Mockito 5 cannot mock on
 * recent JVMs. Built on top of mocked repositories.
 */
final class TestDoubles {

    static FakeUserGroups userGroups() {
        return new FakeUserGroups();
    }

    static RecordingAuditLog auditLog() {
        return new RecordingAuditLog();
    }

    /** Wraps the real BatchAccessService against the supplied repo + groups fake. */
    static BatchAccessService batchAccess(final BatchRepository batchRepository,
                                          final UserGroupsService userGroups) {
        return new BatchAccessService(batchRepository, userGroups);
    }

    /** Wraps the real DocumentAccessService against the supplied repos + groups fake. */
    static DocumentAccessService documentAccess(final BatchRepository batchRepository,
                                                final DocumentRepository documentRepository,
                                                final UserGroupsService userGroups) {
        final BatchAccessService batchAccess = batchAccess(batchRepository, userGroups);
        return new DocumentAccessService(batchRepository, documentRepository, batchAccess);
    }

    static final class FakeUserGroups extends UserGroupsService {
        private final Map<String, Set<String>> byEmail = new HashMap<>();

        FakeUserGroups() {
            super(mock(UserRepository.class), mock(GroupRepository.class));
        }

        FakeUserGroups withMembership(final String email, final Set<String> groupIds) {
            byEmail.put(email, groupIds);
            return this;
        }

        @Override
        public Set<String> groupIdsForEmail(final String email) {
            return email == null ? Set.of() : byEmail.getOrDefault(email, Set.of());
        }
    }

    static final class RecordingAuditLog extends AuditLogService {
        final List<Entry> entries = new ArrayList<>();

        RecordingAuditLog() {
            super(mock(AuditLogRepository.class), mock(UserRepository.class));
        }

        @Override
        public void log(final String action, final String resourceType, final String resourceId,
                        final Map<String, Object> details) {
            entries.add(new Entry(action, resourceType, resourceId, details));
        }

        @Override
        public void log(final String action, final String resourceType, final String resourceId) {
            log(action, resourceType, resourceId, null);
        }

        @Override
        public void logForUser(final String userEmail, final String action, final String resourceType,
                               final String resourceId, final String outcome,
                               final Map<String, Object> details) {
            entries.add(new Entry(action, resourceType, resourceId, details));
        }

        boolean hasAction(final String action) {
            return entries.stream().anyMatch(e -> action.equals(e.action));
        }

        record Entry(String action, String resourceType, String resourceId,
                     Map<String, Object> details) {}
    }

    private TestDoubles() {}
}
