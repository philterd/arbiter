package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.WeightSet;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WeightSetRepository extends MongoRepository<WeightSet, String> {
    Optional<WeightSet> findByName(String name);
}
