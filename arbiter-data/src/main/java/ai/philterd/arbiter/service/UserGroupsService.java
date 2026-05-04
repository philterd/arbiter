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

    public UserGroupsService(UserRepository userRepository, GroupRepository groupRepository) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
    }

    public Set<String> groupIdsForEmail(String email) {
        if (email == null || email.isBlank()) return Set.of();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getId() == null) return Set.of();
        List<Group> groups = groupRepository.findByUserIdsContaining(user.getId());
        Set<String> ids = new HashSet<>();
        for (Group g : groups) {
            if (g.getId() != null) ids.add(g.getId());
        }
        return ids;
    }
}
