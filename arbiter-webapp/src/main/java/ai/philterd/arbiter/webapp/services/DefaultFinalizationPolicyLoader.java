/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.services;

import ai.philterd.arbiter.model.FinalizationPolicy;
import ai.philterd.arbiter.repository.FinalizationPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class DefaultFinalizationPolicyLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultFinalizationPolicyLoader.class);

    private final FinalizationPolicyRepository finalizationPolicyRepository;

    public DefaultFinalizationPolicyLoader(final FinalizationPolicyRepository finalizationPolicyRepository) {
        this.finalizationPolicyRepository = finalizationPolicyRepository;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (finalizationPolicyRepository.findByName("Default").isPresent()) {
            return;
        }
        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId(UUID.randomUUID().toString());
        policy.setName("Default");
        policy.setOption(FinalizationPolicy.OPTION_LEGAL_HOLD);
        policy.setCreatedAt(Instant.now());
        policy.setUpdatedAt(Instant.now());
        finalizationPolicyRepository.save(policy);
        log.info("Seeded default finalization policy 'Default'.");
    }
}
