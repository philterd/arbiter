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

@Document(collection = "user_settings")
public class UserSettings {

    /** Sort the review-page Previous/Next navigation by document risk score (highest first). */
    public static final String SORT_RISK_SCORE = "RISK_SCORE";
    /** Sort by document priority (highest first). */
    public static final String SORT_PRIORITY = "PRIORITY";
    /** Sort by document filename (alphabetical). */
    public static final String SORT_FILENAME = "FILENAME";

    public static boolean isValidReviewSortBy(final String value) {
        return SORT_RISK_SCORE.equals(value) || SORT_PRIORITY.equals(value) || SORT_FILENAME.equals(value);
    }

    @Id
    private String id;

    private String userId;

    private boolean skipCompletedInReview = false;

    private boolean advanceToNextOnApprove = true;

    private String reviewSortBy = SORT_RISK_SCORE;

    public UserSettings() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(final String userId) { this.userId = userId; }

    public boolean isSkipCompletedInReview() { return skipCompletedInReview; }
    public void setSkipCompletedInReview(final boolean skipCompletedInReview) { this.skipCompletedInReview = skipCompletedInReview; }

    public boolean isAdvanceToNextOnApprove() { return advanceToNextOnApprove; }
    public void setAdvanceToNextOnApprove(final boolean advanceToNextOnApprove) { this.advanceToNextOnApprove = advanceToNextOnApprove; }

    public String getReviewSortBy() { return reviewSortBy; }
    public void setReviewSortBy(final String reviewSortBy) { this.reviewSortBy = reviewSortBy; }
}
