/*
 * Copyright 2026 Philterd
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

@Document(collection = "user_settings")
public class UserSettings {

    @Id
    private String id;

    private String userId;

    private boolean skipCompletedInReview = false;

    private boolean advanceToNextOnApprove = false;

    public UserSettings() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public boolean isSkipCompletedInReview() { return skipCompletedInReview; }
    public void setSkipCompletedInReview(boolean skipCompletedInReview) { this.skipCompletedInReview = skipCompletedInReview; }

    public boolean isAdvanceToNextOnApprove() { return advanceToNextOnApprove; }
    public void setAdvanceToNextOnApprove(boolean advanceToNextOnApprove) { this.advanceToNextOnApprove = advanceToNextOnApprove; }
}
