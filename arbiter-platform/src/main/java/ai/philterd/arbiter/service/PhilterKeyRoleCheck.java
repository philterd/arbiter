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

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Checks once at startup whether each Philter instance's API key belongs to a Philter administrator, so
 * keys saved before Arbiter refused administrators' keys are flagged under Admin → Philter. The key
 * keeps working; it is flagged, not refused. Runs off the startup thread, so an unreachable Philter
 * does not delay startup; such an instance stays as it was.
 */
@Component
public class PhilterKeyRoleCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PhilterKeyRoleCheck.class);

    private final PhilterInstanceRepository repository;
    private final PhilterInstanceClients clients;

    public PhilterKeyRoleCheck(final PhilterInstanceRepository repository, final PhilterInstanceClients clients) {
        this.repository = repository;
        this.clients = clients;
    }

    @Override
    public void run(final ApplicationArguments args) {
        Thread.ofVirtual().name("philter-key-role-check").start(this::checkAll);
    }

    void checkAll() {
        for (final PhilterInstance instance : repository.findAll()) {
            if (!clients.hasApiKey(instance)) {
                continue;
            }
            try {
                final Boolean admin = clients.keyBelongsToAdministrator(instance);
                // Saved on a fresh read, and only if the key is still the one checked: an administrator
                // may have changed the instance while Philter was being asked.
                repository.findById(instance.getId()).ifPresent(fresh -> {
                    if (Objects.equals(fresh.getEncryptedApiKey(), instance.getEncryptedApiKey())
                            && !Objects.equals(admin, fresh.getApiKeyAdmin())) {
                        fresh.setApiKeyAdmin(admin);
                        repository.save(fresh);
                    }
                });
                if (Boolean.TRUE.equals(admin)) {
                    log.warn("The API key of Philter instance \"{}\" belongs to a Philter administrator. "
                            + "Remove that user's administrator role in Philter and narrow the key; see "
                            + "Admin > Philter.", instance.getName());
                }
            } catch (Exception e) {
                log.info("Could not check the API key role of Philter instance \"{}\": {}",
                        instance.getName(), PhilterClients.describe(e));
            }
        }
    }
}
