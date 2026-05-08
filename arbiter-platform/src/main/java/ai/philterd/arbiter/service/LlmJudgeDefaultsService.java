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

import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.repository.LlmJudgeDefaultsRepository;
import org.springframework.stereotype.Service;

@Service
public class LlmJudgeDefaultsService {

    private final LlmJudgeDefaultsRepository repository;

    public LlmJudgeDefaultsService(final LlmJudgeDefaultsRepository repository) {
        this.repository = repository;
    }

    public LlmJudgeDefaults load() {
        return repository.findById(LlmJudgeDefaults.SINGLETON_ID).orElseGet(LlmJudgeDefaults::new);
    }

    public LlmJudgeDefaults save(final LlmJudgeDefaults defaults) {
        defaults.setId(LlmJudgeDefaults.SINGLETON_ID);
        return repository.save(defaults);
    }
}
