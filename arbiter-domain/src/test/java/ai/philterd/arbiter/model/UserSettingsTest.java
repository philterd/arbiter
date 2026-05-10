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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsTest {

    @Test
    void isValidReviewSortByAcceptsTheThreeKnownConstants() {
        // Drawn from the constants on UserSettings — accepting unknown strings would let
        // a hand-crafted form submission inject an arbitrary sort field.
        assertTrue(UserSettings.isValidReviewSortBy(UserSettings.SORT_RISK_SCORE));
        assertTrue(UserSettings.isValidReviewSortBy(UserSettings.SORT_PRIORITY));
        assertTrue(UserSettings.isValidReviewSortBy(UserSettings.SORT_FILENAME));
    }

    @Test
    void isValidReviewSortByRejectsNullAndBlank() {
        assertFalse(UserSettings.isValidReviewSortBy(null));
        assertFalse(UserSettings.isValidReviewSortBy(""));
        assertFalse(UserSettings.isValidReviewSortBy("   "));
    }

    @Test
    void isValidReviewSortByIsCaseSensitive() {
        // The constants are documented as upper-case; lower-case inputs must NOT be
        // silently accepted, otherwise the validator becomes a hint rather than a gate.
        assertFalse(UserSettings.isValidReviewSortBy("risk_score"));
        assertFalse(UserSettings.isValidReviewSortBy("Priority"));
        assertFalse(UserSettings.isValidReviewSortBy("FileName"));
    }

    @Test
    void isValidReviewSortByRejectsArbitraryFieldNames() {
        // Defense against a form-fiddling user submitting a Mongo field name. The
        // validator must NOT accept anything outside the curated list.
        assertFalse(UserSettings.isValidReviewSortBy("status"));
        assertFalse(UserSettings.isValidReviewSortBy("createdAt"));
        assertFalse(UserSettings.isValidReviewSortBy("$where: 1==1"));
    }

    @Test
    void defaultSettingsMatchDocumentedDefaults() {
        // A freshly-constructed UserSettings must match the documented defaults — these
        // are the values shown to a user on first sign-in. Drift here would silently
        // change reviewer experience.
        final UserSettings s = new UserSettings();
        assertFalse(s.isSkipCompletedInReview(),
                "Skip-completed defaults to off — most reviewers want to see what they've already done.");
        assertTrue(s.isAdvanceToNextOnApprove(),
                "Advance-to-next defaults to on — a reviewer's hands stay on the keyboard.");
        assertEquals(UserSettings.SORT_RISK_SCORE, s.getReviewSortBy(),
                "Default sort is by risk score so the highest-risk documents come up first.");
    }
}
