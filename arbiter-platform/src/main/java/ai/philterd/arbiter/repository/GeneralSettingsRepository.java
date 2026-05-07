package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.GeneralSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GeneralSettingsRepository extends MongoRepository<GeneralSettings, String> {
}
