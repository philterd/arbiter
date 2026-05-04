package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.repository.LlmJudgeDefaultsRepository;
import org.springframework.stereotype.Service;

@Service
public class LlmJudgeDefaultsService {

    private final LlmJudgeDefaultsRepository repository;

    public LlmJudgeDefaultsService(LlmJudgeDefaultsRepository repository) {
        this.repository = repository;
    }

    public LlmJudgeDefaults load() {
        return repository.findById(LlmJudgeDefaults.SINGLETON_ID).orElseGet(LlmJudgeDefaults::new);
    }

    public LlmJudgeDefaults save(LlmJudgeDefaults defaults) {
        defaults.setId(LlmJudgeDefaults.SINGLETON_ID);
        return repository.save(defaults);
    }
}
