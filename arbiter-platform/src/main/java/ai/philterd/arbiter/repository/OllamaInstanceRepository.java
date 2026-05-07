package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.OllamaInstance;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OllamaInstanceRepository extends MongoRepository<OllamaInstance, String> {
    Optional<OllamaInstance> findByName(String name);
}
