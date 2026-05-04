package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Batch;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BatchRepository extends MongoRepository<Batch, String> {
    Optional<Batch> findByName(String name);
    boolean existsByWeightSetId(String weightSetId);
    java.util.List<Batch> findByWeightSetId(String weightSetId);
    java.util.List<Batch> findByPhilterInstanceIdIsNullAndPolicyName(String policyName);
}
