package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Group;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends MongoRepository<Group, String> {
    Optional<Group> findByName(String name);

    java.util.List<Group> findByUserIdsContaining(String userId);
}
