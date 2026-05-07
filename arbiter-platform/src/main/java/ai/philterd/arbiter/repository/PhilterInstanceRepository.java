package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.PhilterInstance;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PhilterInstanceRepository extends MongoRepository<PhilterInstance, String> {
    Optional<PhilterInstance> findByName(String name);
}
