package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.NotificationSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationSettingsRepository extends MongoRepository<NotificationSettings, String> {
}
