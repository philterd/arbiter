package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
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
