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

import ai.philterd.arbiter.model.PhilterDefaults;
import ai.philterd.arbiter.repository.PhilterDefaultsRepository;
import org.springframework.stereotype.Service;

@Service
public class PhilterDefaultsService {

    private final PhilterDefaultsRepository repository;

    public PhilterDefaultsService(final PhilterDefaultsRepository repository) {
        this.repository = repository;
    }

    public PhilterDefaults load() {
        return repository.findById(PhilterDefaults.SINGLETON_ID).orElseGet(PhilterDefaults::new);
    }

    public PhilterDefaults save(final PhilterDefaults defaults) {
        defaults.setId(PhilterDefaults.SINGLETON_ID);
        return repository.save(defaults);
    }
}
