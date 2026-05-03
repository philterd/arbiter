package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Batch;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BatchRepository extends MongoRepository<Batch, String> {
}
