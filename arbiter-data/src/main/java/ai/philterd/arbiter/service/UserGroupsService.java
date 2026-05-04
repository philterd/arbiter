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
}
