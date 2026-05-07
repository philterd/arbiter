package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.PhilterDefaults;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PhilterDefaultsRepository extends MongoRepository<PhilterDefaults, String> {
}
