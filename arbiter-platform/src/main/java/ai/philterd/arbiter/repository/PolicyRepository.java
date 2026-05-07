package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Policy;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PolicyRepository extends MongoRepository<Policy, String> {
    Optional<Policy> findByName(String name);
    Optional<Policy> findFirstByNameIgnoreCase(String name);
}
