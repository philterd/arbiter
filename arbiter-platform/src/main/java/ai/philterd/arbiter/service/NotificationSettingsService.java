/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
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
