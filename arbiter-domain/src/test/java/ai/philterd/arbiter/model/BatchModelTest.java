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

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Batch} is mostly POJO accessors, but {@link Batch#effectiveRuleSets()} contains
 * read-side migration logic that synthesizes a single rule set from the legacy fields when
 * the new {@code approvalRuleSets} field is empty. That migration runs on every read of an
 * old batch — a regression would silently misevaluate dual-approval rules.
 */
class BatchModelTest {

    @Test
    void effectiveRuleSetsReturnsNewSetsWhenPopulated() {
        final Batch b = new Batch();
        final ApprovalRuleSet set = new ApprovalRuleSet();
        set.setId("set-1");
        b.setApprovalRuleSets(new java.util.ArrayList<>(List.of(set)));

        final List<ApprovalRuleSet> effective = b.effectiveRuleSets();
        assertEquals(1, effective.size());
        assertEquals("set-1", effective.get(0).getId(),
                "When the new field has rule sets, the legacy synthesis path must NOT run.");
    }

    @Test
    void effectiveRuleSetsReturnsEmptyWhenBothNewAndLegacyAreEmpty() {
        // No rule sets in either field → no dual-approval rules apply.
        final Batch b = new Batch();
        assertTrue(b.effectiveRuleSets().isEmpty(),
                "A batch with no rules in either the new or legacy fields must yield zero rule sets.");
    }

    @Test
    void effectiveRuleSetsSynthesizesLegacyFieldsIntoOneSet() {
        // Legacy data: approvalRuleNames + the four threshold fields. The migration must
        // surface them as a single ApprovalRuleSet so the evaluator's existing logic works
        // unchanged on legacy batches.
        final Batch b = new Batch();
        b.setId("legacy-batch");
        @SuppressWarnings("deprecation")
        final var ignored = b; // keep the deprecation warning suppressed for the calls below
        b.setApprovalRuleNames(new LinkedHashSet<>(List.of("HIGH_RISK", "REJECTED_HIGH_CONFIDENCE")));
        b.setRiskScoreRuleThreshold(0.8);
        b.setRejectedConfidenceRuleThreshold(0.85);
        b.setExperiencedReviewerRuleThreshold(50L);

        final List<ApprovalRuleSet> effective = b.effectiveRuleSets();
        assertEquals(1, effective.size(),
                "Legacy fields collapse into exactly one synthesized rule set.");
        final ApprovalRuleSet synthesised = effective.get(0);
        // The synthesised id is derived from the batch id so the rule set is stable across reads.
        assertNotNull(synthesised.getId());
        assertTrue(synthesised.getId().contains("legacy-batch"),
                "Synthesized id must reference the originating batch id; got " + synthesised.getId());
        assertEquals(2, synthesised.getRules().size());
        assertEquals(0.8, synthesised.getRiskScoreThreshold());
        assertEquals(0.85, synthesised.getRejectedConfidenceThreshold());
        assertEquals(50L, synthesised.getExperiencedReviewerThreshold());
    }

    @Test
    void effectiveRuleSetsHandlesBatchWithoutId() {
        // The synthesised id falls back to "legacy" when the batch has no id yet (e.g.
        // a freshly-created Batch in tests). Must not crash.
        final Batch b = new Batch();
        b.setId(null);
        b.setApprovalRuleNames(new LinkedHashSet<>(List.of("HIGH_RISK")));

        final List<ApprovalRuleSet> effective = b.effectiveRuleSets();
        assertEquals(1, effective.size());
        assertNotNull(effective.get(0).getId(),
                "Synthesized rule set must always carry an id, even when the batch has none.");
    }

    @Test
    void getApprovalRuleSetsLazilyInitialisesNullField() {
        // Old documents loaded from Mongo before the field existed have a null value.
        // The getter must return an empty mutable list rather than null.
        final Batch b = new Batch();
        b.setApprovalRuleSets(null);
        assertNotNull(b.getApprovalRuleSets());
        b.getApprovalRuleSets().add(new ApprovalRuleSet());
        assertEquals(1, b.getApprovalRuleSets().size(),
                "The lazily-initialised list must be mutable.");
    }

    @Test
    void getContextNeverReturnsNull() {
        // The free-form context field is sent to Philter on every redaction — a null
        // value would crash downstream string concatenation. The getter normalises null
        // to empty so callers don't need a defensive check.
        final Batch b = new Batch();
        b.setContext(null);
        assertEquals("", b.getContext());
    }

    @Test
    void setContextNullStoresEmptyString() {
        final Batch b = new Batch();
        b.setContext(null);
        assertEquals("", b.getContext());
    }

    @Test
    void blindDoubleReviewDefaultsAreOffWithTenPercent() {
        // A freshly-constructed batch must default the feature off with the percentage at
        // the documented default of 10. Critical so a legacy batch read from Mongo without
        // the field doesn't accidentally route every document into the second-review pool.
        final Batch b = new Batch();
        assertEquals(false, b.isBlindDoubleReviewEnabled());
        assertEquals(10, b.getBlindDoubleReviewPercentage());
    }

    @Test
    void exemptionCodeRequiredDefaultsTrue() {
        // Existing behavior is preserved for legacy batches loaded from Mongo before the
        // field was introduced — they default to requiring an exemption code.
        assertTrue(new Batch().isExemptionCodeRequired());
    }

    @Test
    void confidenceAndDocumentThresholdsHaveSensibleDefaults() {
        final Batch b = new Batch();
        assertEquals(0.8, b.getConfidenceThreshold(), 1e-9,
                "Default confidence threshold (0.8) is the documented PII auto-accept floor.");
        assertEquals(0.25, b.getDocumentThreshold(), 1e-9,
                "Default document threshold (0.25) is the documented auto-approve ceiling.");
        assertEquals(0.10, b.getAuditSamplingRate(), 1e-9,
                "Default audit sampling rate (10%) matches the docs.");
    }
}
