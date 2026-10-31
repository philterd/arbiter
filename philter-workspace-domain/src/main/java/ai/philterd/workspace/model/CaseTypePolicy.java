/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

/**
 * One entry of the case-type mapping (#16): documents of {@link #caseType}, in a batch that chooses its policy by
 * case type, are redacted with Philter's policy {@link #policyName}.
 */
public class CaseTypePolicy {

    private String caseType;
    private String policyName;

    public CaseTypePolicy() {
    }

    public CaseTypePolicy(final String caseType, final String policyName) {
        this.caseType = caseType;
        this.policyName = policyName;
    }

    public String getCaseType() { return caseType; }
    public void setCaseType(final String caseType) { this.caseType = caseType; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(final String policyName) { this.policyName = policyName; }
}
