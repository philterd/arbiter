/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class LlmJudgeDefaults {

    public static final String SINGLETON_ID = "llm-judge-defaults";

    @Id
    private String id = SINGLETON_ID;

    private String explainInstanceId;
    private String explainModel;

    private String secondOpinionInstanceId;
    private String secondOpinionModel;

    public LlmJudgeDefaults() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getExplainInstanceId() { return explainInstanceId; }
    public void setExplainInstanceId(final String explainInstanceId) { this.explainInstanceId = explainInstanceId; }

    public String getExplainModel() { return explainModel; }
    public void setExplainModel(final String explainModel) { this.explainModel = explainModel; }

    public String getSecondOpinionInstanceId() { return secondOpinionInstanceId; }
    public void setSecondOpinionInstanceId(final String secondOpinionInstanceId) { this.secondOpinionInstanceId = secondOpinionInstanceId; }

    public String getSecondOpinionModel() { return secondOpinionModel; }
    public void setSecondOpinionModel(final String secondOpinionModel) { this.secondOpinionModel = secondOpinionModel; }
}
