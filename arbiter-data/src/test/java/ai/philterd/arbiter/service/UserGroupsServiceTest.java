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

    private static User user(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private static Group group(String id, String name) {
        Group g = new Group();
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
        User u = user("u1", "alice@example.com");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of());
        assertTrue(service.groupIdsForEmail("alice@example.com").isEmpty());
    }

    @Test
    void returnsAllGroupIdsForUser() {
        User u = user("u1", "alice@example.com");
        Group g1 = group("g1", "Reviewers");
        Group g2 = group("g2", "Auditors");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of(g1, g2));

        Set<String> ids = service.groupIdsForEmail("alice@example.com");
        assertEquals(Set.of("g1", "g2"), ids);
    }

    @Test
    void groupsWithNullIdAreSkipped() {
        User u = user("u1", "alice@example.com");
        Group good = group("g1", "Reviewers");
        Group ghost = group(null, "Ghost");
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(groups.findByUserIdsContaining("u1")).thenReturn(List.of(good, ghost));

        Set<String> ids = service.groupIdsForEmail("alice@example.com");
        assertEquals(Set.of("g1"), ids);
    }
}
