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

import ai.philterd.arbiter.model.ApprovalRule;
import ai.philterd.arbiter.model.ApprovalRuleSet;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.model.User;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApprovalRuleEvaluator}, covering each rule in isolation,
 * AND-within-rule-set semantics, OR-across-rule-set semantics, and the worst-case
 * {@code approvalsRequired()} path used by the queue display.
 */
class ApprovalRuleEvaluatorTest {

    private final ApprovalRuleEvaluator evaluator = new ApprovalRuleEvaluator();

    // ------------------------------------------------------------------ helpers

    private static Batch batchWith(final ApprovalRuleSet... sets) {
        final Batch b = new Batch();
        b.setId("batch-1");
        final List<ApprovalRuleSet> list = new ArrayList<>();
        Collections.addAll(list, sets);
        b.setApprovalRuleSets(list);
        return b;
    }

    private static ApprovalRuleSet setOf(final String... rules) {
        final ApprovalRuleSet s = new ApprovalRuleSet();
        s.setId("rs-" + String.join("-", rules));
        final java.util.Set<String> r = new LinkedHashSet<>();
        Collections.addAll(r, rules);
        s.setRules(r);
        return s;
    }

    private static Document doc() {
        return new Document();
    }

    private static Document doc(final String text) {
        final Document d = new Document();
        d.setOriginalText(text);
        return d;
    }

    private static Span span(final String type, final double confidence, final String status) {
        final Span s = new Span();
        s.setType(type);
        s.setConfidence(confidence);
        s.changeStatus(status);
        return s;
    }

    private static Span manualSpan() {
        final Span s = new Span();
        s.setType("MANUAL");
        s.setManuallyCreated(true);
        s.changeStatus("APPROVED");
        return s;
    }

    private static User reviewer(final long reviewCount) {
        final User u = new User();
        u.setReviewCount(reviewCount);
        return u;
    }

    // ------------------------------------------------------------------ batch-level edge cases

    @Test
    void nullBatchNeverRequiresDualApproval() {
        assertFalse(evaluator.dualApprovalRequired(null, doc(), List.of(), reviewer(0)));
        assertEquals(1, evaluator.approvalsRequired(null, doc(), List.of()));
    }

    @Test
    void batchWithNoRuleSetsNeverRequiresDualApproval() {
        final Batch batch = batchWith();
        assertFalse(evaluator.dualApprovalRequired(batch, doc(), List.of(), reviewer(0)));
        assertEquals(1, evaluator.approvalsRequired(batch, doc(), List.of()));
    }

    @Test
    void emptyRuleSetNeverFires() {
        final Batch batch = batchWith(new ApprovalRuleSet());
        assertFalse(evaluator.dualApprovalRequired(batch, doc(), List.of(), reviewer(0)));
        assertEquals(1, evaluator.approvalsRequired(batch, doc(), List.of()));
    }

    @Test
    void unknownRuleNameFailsClosed() {
        final Batch batch = batchWith(setOf("NOT_A_REAL_RULE"));
        assertFalse(evaluator.dualApprovalRequired(batch, doc(), List.of(), reviewer(0)));
    }

    // ------------------------------------------------------------------ per-rule unit tests

    @Nested
    class SensitivePiiRule {
        @Test
        void firesOnSsn() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertTrue(evaluator.dualApprovalRequired(b, doc(),
                    List.of(span("ssn", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void firesOnCreditCard() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertTrue(evaluator.dualApprovalRequired(b, doc(),
                    List.of(span("credit-card", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void firesOnPassportNumber() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertTrue(evaluator.dualApprovalRequired(b, doc(),
                    List.of(span("passport-number", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void caseInsensitiveSpanType() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertTrue(evaluator.dualApprovalRequired(b, doc(),
                    List.of(span("SSN", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void doesNotFireForUnrelatedTypes() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertFalse(evaluator.dualApprovalRequired(b, doc(),
                    List.of(span("email", 0.95, "APPROVED")), reviewer(0)));
        }

        @Test
        void doesNotFireWhenSpansNull() {
            final Batch b = batchWith(setOf(ApprovalRule.SENSITIVE_PII));
            assertFalse(evaluator.dualApprovalRequired(b, doc(), null, reviewer(0)));
        }
    }

    @Nested
    class HighRiskScoreRule {
        @Test
        void firesAboveThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.5);
            final Document d = doc();
            d.setRiskScore(0.6);
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireAtThreshold() {
            // The evaluator uses a strict greater-than comparison; equal values do not fire.
            final ApprovalRuleSet s = setOf(ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.5);
            final Document d = doc();
            d.setRiskScore(0.5);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireBelowThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.9);
            final Document d = doc();
            d.setRiskScore(0.4);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }
    }

    @Nested
    class RejectedHighConfidenceRule {
        @Test
        void firesWhenAnyRejectedSpanExceedsThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.REJECTED_HIGH_CONFIDENCE);
            s.setRejectedConfidenceThreshold(0.9);
            final List<Span> spans = List.of(
                    span("name", 0.5, "APPROVED"),
                    span("name", 0.99, "REJECTED"));
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void ignoresHighConfidenceWhenNotRejected() {
            final ApprovalRuleSet s = setOf(ApprovalRule.REJECTED_HIGH_CONFIDENCE);
            s.setRejectedConfidenceThreshold(0.9);
            // High confidence but APPROVED, not REJECTED.
            final List<Span> spans = List.of(span("name", 0.99, "APPROVED"));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void doesNotFireWhenAllRejectedAreLowConfidence() {
            final ApprovalRuleSet s = setOf(ApprovalRule.REJECTED_HIGH_CONFIDENCE);
            s.setRejectedConfidenceThreshold(0.9);
            final List<Span> spans = List.of(span("name", 0.5, "REJECTED"));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void doesNotFireWhenSpansNull() {
            final ApprovalRuleSet s = setOf(ApprovalRule.REJECTED_HIGH_CONFIDENCE);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), null, reviewer(0)));
        }
    }

    @Nested
    class InexperiencedReviewerRule {
        @Test
        void firesWhenReviewerHasFewerReviews() {
            final ApprovalRuleSet s = setOf(ApprovalRule.INEXPERIENCED_REVIEWER);
            s.setExperiencedReviewerThreshold(50);
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), doc(), List.of(), reviewer(10)));
        }

        @Test
        void doesNotFireAtOrAboveThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.INEXPERIENCED_REVIEWER);
            s.setExperiencedReviewerThreshold(50);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), List.of(), reviewer(50)));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), List.of(), reviewer(99)));
        }

        @Test
        void firesWorstCaseWhenReviewerNull() {
            // For queue display the eventual reviewer is unknown; the evaluator treats null
            // reviewers as inexperienced so the queue surfaces the conservative count.
            final ApprovalRuleSet s = setOf(ApprovalRule.INEXPERIENCED_REVIEWER);
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), doc(), List.of(), null));
        }
    }

    @Nested
    class ManualSpansThresholdRule {
        @Test
        void firesWhenManualCountExceedsThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.MANUAL_SPANS_THRESHOLD);
            s.setManualSpansThreshold(2);
            final List<Span> spans = List.of(manualSpan(), manualSpan(), manualSpan());
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void doesNotFireAtThreshold() {
            final ApprovalRuleSet s = setOf(ApprovalRule.MANUAL_SPANS_THRESHOLD);
            s.setManualSpansThreshold(3);
            final List<Span> spans = List.of(manualSpan(), manualSpan(), manualSpan());
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void ignoresNonManualSpans() {
            final ApprovalRuleSet s = setOf(ApprovalRule.MANUAL_SPANS_THRESHOLD);
            s.setManualSpansThreshold(0);
            final List<Span> spans = List.of(span("name", 0.9, "APPROVED"));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), spans, reviewer(0)));
        }

        @Test
        void doesNotFireWhenSpansNull() {
            final ApprovalRuleSet s = setOf(ApprovalRule.MANUAL_SPANS_THRESHOLD);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), doc(), null, reviewer(0)));
        }
    }

    @Nested
    class ClassifiedKeywordsRule {
        @Test
        void firesOnCaseInsensitiveSubstring() {
            final ApprovalRuleSet s = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            s.setClassifiedKeywords(List.of("Classified", "Secret"));
            assertTrue(evaluator.dualApprovalRequired(batchWith(s),
                    doc("This memo is CLASSIFIED material."), List.of(), reviewer(0)));
            assertTrue(evaluator.dualApprovalRequired(batchWith(s),
                    doc("Top secret document"), List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireWhenNoKeywordsPresent() {
            final ApprovalRuleSet s = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            s.setClassifiedKeywords(List.of("Classified"));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s),
                    doc("Routine quarterly summary."), List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireWhenKeywordListIsEmpty() {
            final ApprovalRuleSet s = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            s.setClassifiedKeywords(List.of());
            assertFalse(evaluator.dualApprovalRequired(batchWith(s),
                    doc("classified material"), List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireWhenDocumentTextNull() {
            final ApprovalRuleSet s = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            s.setClassifiedKeywords(List.of("Classified"));
            assertFalse(evaluator.dualApprovalRequired(batchWith(s),
                    doc(), List.of(), reviewer(0)));
        }

        @Test
        void ignoresBlankKeywords() {
            final ApprovalRuleSet s = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            s.setClassifiedKeywords(java.util.Arrays.asList("", "  ", null, "Secret"));
            assertTrue(evaluator.dualApprovalRequired(batchWith(s),
                    doc("a secret note"), List.of(), reviewer(0)));
        }
    }

    @Nested
    class DualApprovalSamplingRateRule {
        @Test
        void firesWhenRollBelowRate() {
            final ApprovalRuleSet s = setOf(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
            s.setDualApprovalSamplingRate(0.5);
            final Document d = doc();
            d.setDualApprovalSamplingRoll(0.1);
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void doesNotFireWhenRollAtOrAboveRate() {
            final ApprovalRuleSet s = setOf(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
            s.setDualApprovalSamplingRate(0.5);
            final Document d = doc();
            d.setDualApprovalSamplingRoll(0.5);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
            d.setDualApprovalSamplingRoll(0.9);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void zeroRateNeverFires() {
            final ApprovalRuleSet s = setOf(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
            s.setDualApprovalSamplingRate(0.0);
            final Document d = doc();
            d.setDualApprovalSamplingRoll(0.0);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void rateAtOrAboveOneAlwaysFires() {
            final ApprovalRuleSet s = setOf(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
            s.setDualApprovalSamplingRate(1.0);
            final Document d = doc();
            // Even with no roll persisted, rate >= 1 short-circuits to true.
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }

        @Test
        void documentsPersistedBeforeSamplingRollAreNotSampled() {
            final ApprovalRuleSet s = setOf(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
            s.setDualApprovalSamplingRate(0.5);
            final Document d = doc(); // no roll set
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), d, List.of(), reviewer(0)));
        }
    }

    // ------------------------------------------------------------------ AND-within-set

    @Nested
    class AndWithinRuleSet {
        @Test
        void firesOnlyWhenAllConditionsMatch() {
            final ApprovalRuleSet s = setOf(ApprovalRule.SENSITIVE_PII, ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.5);

            final Document d = doc();
            d.setRiskScore(0.6);

            assertTrue(evaluator.dualApprovalRequired(batchWith(s), d,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));
        }

        @Test
        void anySingleConditionFalseDefeatsTheSet() {
            final ApprovalRuleSet s = setOf(ApprovalRule.SENSITIVE_PII, ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.5);

            // Sensitive PII present, but risk score below threshold → set does not fire.
            final Document low = doc();
            low.setRiskScore(0.2);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), low,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));

            // Risk score above threshold, but no sensitive span → set does not fire.
            final Document high = doc();
            high.setRiskScore(0.9);
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), high,
                    List.of(span("name", 0.9, "APPROVED")), reviewer(0)));
        }

        @Test
        void threeConditionFanIn_eachOneMissingDefeatsTheSet() {
            // (sensitive PII) AND (risk > 0.5) AND (inexperienced reviewer < 50)
            final ApprovalRuleSet s = setOf(
                    ApprovalRule.SENSITIVE_PII,
                    ApprovalRule.HIGH_RISK_SCORE,
                    ApprovalRule.INEXPERIENCED_REVIEWER);
            s.setRiskScoreThreshold(0.5);
            s.setExperiencedReviewerThreshold(50);

            final Document riskyDoc = doc();
            riskyDoc.setRiskScore(0.8);
            final Document calmDoc = doc();
            calmDoc.setRiskScore(0.1);
            final List<Span> ssn = List.of(span("ssn", 0.9, "APPROVED"));
            final List<Span> nameOnly = List.of(span("name", 0.9, "APPROVED"));

            // All three true → fire.
            assertTrue(evaluator.dualApprovalRequired(batchWith(s), riskyDoc, ssn, reviewer(10)));

            // SENSITIVE_PII missing.
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), riskyDoc, nameOnly, reviewer(10)));

            // HIGH_RISK_SCORE missing.
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), calmDoc, ssn, reviewer(10)));

            // INEXPERIENCED_REVIEWER missing (reviewer at threshold).
            assertFalse(evaluator.dualApprovalRequired(batchWith(s), riskyDoc, ssn, reviewer(50)));
        }
    }

    // ------------------------------------------------------------------ OR-across-sets

    @Nested
    class OrAcrossRuleSets {
        @Test
        void neitherSetFires() {
            final ApprovalRuleSet a = setOf(ApprovalRule.SENSITIVE_PII);
            final ApprovalRuleSet b = setOf(ApprovalRule.HIGH_RISK_SCORE);
            b.setRiskScoreThreshold(0.5);

            final Document calm = doc();
            calm.setRiskScore(0.1);
            assertFalse(evaluator.dualApprovalRequired(batchWith(a, b), calm,
                    List.of(span("name", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void onlyFirstSetFires() {
            final ApprovalRuleSet a = setOf(ApprovalRule.SENSITIVE_PII);
            final ApprovalRuleSet b = setOf(ApprovalRule.HIGH_RISK_SCORE);
            b.setRiskScoreThreshold(0.5);

            final Document calm = doc();
            calm.setRiskScore(0.1);
            assertTrue(evaluator.dualApprovalRequired(batchWith(a, b), calm,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));
        }

        @Test
        void onlySecondSetFires() {
            final ApprovalRuleSet a = setOf(ApprovalRule.SENSITIVE_PII);
            final ApprovalRuleSet b = setOf(ApprovalRule.HIGH_RISK_SCORE);
            b.setRiskScoreThreshold(0.5);

            final Document risky = doc();
            risky.setRiskScore(0.8);
            assertTrue(evaluator.dualApprovalRequired(batchWith(a, b), risky,
                    List.of(span("name", 0.5, "APPROVED")), reviewer(0)));
        }

        @Test
        void bothSetsFire() {
            final ApprovalRuleSet a = setOf(ApprovalRule.SENSITIVE_PII);
            final ApprovalRuleSet b = setOf(ApprovalRule.HIGH_RISK_SCORE);
            b.setRiskScoreThreshold(0.5);

            final Document risky = doc();
            risky.setRiskScore(0.8);
            assertTrue(evaluator.dualApprovalRequired(batchWith(a, b), risky,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));
        }

        @Test
        void andOrCombination_AandB_OR_C() {
            // Set 1: SENSITIVE_PII AND HIGH_RISK_SCORE > 0.5
            // Set 2: CLASSIFIED_KEYWORDS contains "Secret"
            final ApprovalRuleSet andSet = setOf(
                    ApprovalRule.SENSITIVE_PII, ApprovalRule.HIGH_RISK_SCORE);
            andSet.setRiskScoreThreshold(0.5);
            final ApprovalRuleSet keywordSet = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            keywordSet.setClassifiedKeywords(List.of("Secret"));

            final Batch batch = batchWith(andSet, keywordSet);

            // (true AND true) OR false → true
            final Document risky = doc("nothing classified here");
            risky.setRiskScore(0.8);
            assertTrue(evaluator.dualApprovalRequired(batch, risky,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));

            // (true AND false) OR false → false (sensitive but low risk, no keyword)
            final Document calm = doc("nothing classified here");
            calm.setRiskScore(0.1);
            assertFalse(evaluator.dualApprovalRequired(batch, calm,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));

            // (true AND false) OR true → true (sensitive, low risk, but keyword present)
            final Document calmKeyword = doc("contains Secret material");
            calmKeyword.setRiskScore(0.1);
            assertTrue(evaluator.dualApprovalRequired(batch, calmKeyword,
                    List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));

            // (false AND ?) OR false → false
            final Document plain = doc("plain text");
            plain.setRiskScore(0.8);
            assertFalse(evaluator.dualApprovalRequired(batch, plain,
                    List.of(span("name", 0.9, "APPROVED")), reviewer(0)));
        }

        @Test
        void perRuleSetThresholdsAreIndependent() {
            // Two sets each with HIGH_RISK_SCORE but different thresholds — only the looser
            // one fires for a mid-risk document.
            final ApprovalRuleSet strict = setOf(ApprovalRule.HIGH_RISK_SCORE);
            strict.setRiskScoreThreshold(0.95);
            final ApprovalRuleSet loose = setOf(ApprovalRule.HIGH_RISK_SCORE);
            loose.setRiskScoreThreshold(0.4);

            final Document mid = doc();
            mid.setRiskScore(0.6);

            // strict alone: no fire.
            assertFalse(evaluator.dualApprovalRequired(batchWith(strict), mid,
                    List.of(), reviewer(0)));
            // loose alone: fire.
            assertTrue(evaluator.dualApprovalRequired(batchWith(loose), mid,
                    List.of(), reviewer(0)));
            // strict + loose: OR → fire.
            assertTrue(evaluator.dualApprovalRequired(batchWith(strict, loose), mid,
                    List.of(), reviewer(0)));
        }
    }

    // ------------------------------------------------------------------ approvalsRequired (queue)

    @Nested
    class ApprovalsRequiredForQueue {
        @Test
        void inexperiencedReviewerAloneAlwaysReportsTwo() {
            // Queue display doesn't know who will approve, so the evaluator treats the
            // INEXPERIENCED_REVIEWER rule as worst-case satisfied.
            final ApprovalRuleSet s = setOf(ApprovalRule.INEXPERIENCED_REVIEWER);
            s.setExperiencedReviewerThreshold(0); // even an experienced-only cutoff yields 2.
            assertEquals(2, evaluator.approvalsRequired(batchWith(s), doc(), List.of()));
        }

        @Test
        void inexperiencedAndedWithFalseDocumentRuleReportsOne() {
            // INEXPERIENCED_REVIEWER short-circuits, but the AND-paired document rule still
            // gates the rule set. If it's currently false, the set does not fire.
            final ApprovalRuleSet s = setOf(
                    ApprovalRule.INEXPERIENCED_REVIEWER,
                    ApprovalRule.HIGH_RISK_SCORE);
            s.setRiskScoreThreshold(0.9);
            final Document calm = doc();
            calm.setRiskScore(0.1);
            assertEquals(1, evaluator.approvalsRequired(batchWith(s), calm, List.of()));
        }

        @Test
        void documentSideRulesAllSatisfiedReportsTwo() {
            final ApprovalRuleSet s = setOf(
                    ApprovalRule.HIGH_RISK_SCORE,
                    ApprovalRule.SENSITIVE_PII);
            s.setRiskScoreThreshold(0.5);
            final Document risky = doc();
            risky.setRiskScore(0.9);
            assertEquals(2, evaluator.approvalsRequired(batchWith(s),
                    risky, List.of(span("ssn", 0.9, "APPROVED"))));
        }

        @Test
        void otherRuleSetsCanStillForceTwo_evenIfFirstSetCannotFire() {
            // First set's AND fails (calm doc); second set fires on classified keyword.
            final ApprovalRuleSet a = setOf(ApprovalRule.HIGH_RISK_SCORE);
            a.setRiskScoreThreshold(0.99);
            final ApprovalRuleSet b = setOf(ApprovalRule.CLASSIFIED_KEYWORDS);
            b.setClassifiedKeywords(List.of("Secret"));

            final Document calmKeyword = doc("This is Secret data.");
            calmKeyword.setRiskScore(0.1);
            assertEquals(2, evaluator.approvalsRequired(batchWith(a, b), calmKeyword, List.of()));
        }
    }

    // ------------------------------------------------------------------ legacy back-compat

    @Test
    @SuppressWarnings("deprecation")
    void legacySingleSetFieldsSynthesizeRuleSetWhenNewListEmpty() {
        // Older batches stored a single rule list directly on the batch; effectiveRuleSets()
        // synthesizes a rule set from those legacy fields when the new list is empty.
        final Batch legacy = new Batch();
        legacy.setId("legacy-1");
        legacy.setApprovalRuleNames(new java.util.LinkedHashSet<>(List.of(
                ApprovalRule.SENSITIVE_PII)));

        assertTrue(evaluator.dualApprovalRequired(legacy, doc(),
                List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));
        assertFalse(evaluator.dualApprovalRequired(legacy, doc(),
                List.of(span("name", 0.9, "APPROVED")), reviewer(0)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void newApprovalRuleSetsTakePrecedenceOverLegacyFields() {
        final Batch batch = new Batch();
        batch.setId("hybrid");
        // Legacy field says "always require dual approval on SSN"…
        batch.setApprovalRuleNames(new java.util.LinkedHashSet<>(List.of(
                ApprovalRule.SENSITIVE_PII)));
        // …but the new list is non-empty, so the legacy fields are ignored entirely.
        batch.setApprovalRuleSets(List.of(setOf(ApprovalRule.HIGH_RISK_SCORE)));

        // SSN present, but new list only has HIGH_RISK_SCORE on a low-risk doc → no fire.
        final Document calm = doc();
        calm.setRiskScore(0.0);
        assertFalse(evaluator.dualApprovalRequired(batch, calm,
                List.of(span("ssn", 0.9, "APPROVED")), reviewer(0)));
    }
}
