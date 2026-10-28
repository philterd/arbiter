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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.GeneralSettingsRepository;
import ai.philterd.arbiter.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Removes the credentials Arbiter's local sign-in kept on user rows (password hash, MFA secret, API key)
 * once, after an upgrade. {@link User} no longer has those fields, and both stores write a row whole, so
 * saving each row again leaves them out. A row that cannot be saved is logged and left; the next start
 * tries again until every row has been saved.
 */
@Component
public class LocalCredentialsCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalCredentialsCleanup.class);

    private final UserRepository userRepository;
    private final GeneralSettingsRepository settingsRepository;

    public LocalCredentialsCleanup(final UserRepository userRepository,
                                   final GeneralSettingsRepository settingsRepository) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
    }

    @Override
    public void run(final ApplicationArguments args) {
        // The stored row, not GeneralSettingsService.load(), which fills in read-time defaults that
        // saving would then persist.
        final GeneralSettings settings = settingsRepository.findById(GeneralSettings.SINGLETON_ID)
                .orElseGet(GeneralSettings::new);
        if (settings.isLocalCredentialsRemoved()) {
            return;
        }
        int saved = 0;
        int failed = 0;
        for (final User user : userRepository.findAll()) {
            try {
                userRepository.save(user);
                saved++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not rewrite user record {}: {}", user.getId(), e.getClass().getSimpleName());
            }
        }
        if (failed == 0) {
            settings.setId(GeneralSettings.SINGLETON_ID);
            settings.setLocalCredentialsRemoved(true);
            settingsRepository.save(settings);
        }
        log.info("Removed local sign-in credentials from {} user records ({} failed).", saved, failed);
    }
}
