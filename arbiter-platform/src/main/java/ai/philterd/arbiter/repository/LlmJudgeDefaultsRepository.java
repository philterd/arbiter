package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.LlmJudgeDefaults;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmJudgeDefaultsRepository extends MongoRepository<LlmJudgeDefaults, String> {
}
