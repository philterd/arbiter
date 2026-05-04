package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.repository.NotificationSettingsRepository;
import org.springframework.stereotype.Service;

@Service
public class NotificationSettingsService {

    private final NotificationSettingsRepository repository;

    public NotificationSettingsService(final NotificationSettingsRepository repository) {
        this.repository = repository;
    }

    public NotificationSettings load() {
        return repository.findById(NotificationSettings.SINGLETON_ID).orElseGet(NotificationSettings::new);
    }

    public NotificationSettings save(final NotificationSettings settings) {
        settings.setId(NotificationSettings.SINGLETON_ID);
        return repository.save(settings);
    }
}
