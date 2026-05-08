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

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.model.UserSettings;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

@Service
public class UserSettingsService {

    private final UserSettingsRepository repository;
    private final UserRepository userRepository;

    public UserSettingsService(final UserSettingsRepository repository, final UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public UserSettings loadForEmail(final String email) {
        if (email == null || email.isBlank()) return defaults(null);
        final User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getId() == null) return defaults(null);
        return loadForUserId(user.getId());
    }

    public UserSettings loadForUserId(final String userId) {
        if (userId == null) return defaults(null);
        return repository.findById(userId).orElseGet(() -> defaults(userId));
    }

    public UserSettings save(final UserSettings settings) {
        if (settings.getId() == null) {
            settings.setId(settings.getUserId());
        }
        return repository.save(settings);
    }

    private static UserSettings defaults(final String userId) {
        final UserSettings s = new UserSettings();
        s.setId(userId);
        s.setUserId(userId);
        return s;
    }
}
