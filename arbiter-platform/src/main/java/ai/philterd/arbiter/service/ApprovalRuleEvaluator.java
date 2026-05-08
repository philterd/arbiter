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
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evaluates a batch's approval rule sets against a document being approved. Within a single
 * rule set the rules are AND-ed; across rule sets they are OR-ed (any rule set whose rules all
 * hold triggers dual approval). A batch with no rule sets never requires dual approval.
 */
@Service
public class ApprovalRuleEvaluator {

    /**
     * Returns true when this approval needs a second reviewer: at least one rule set on the
     * batch has all of its rules currently satisfied.
     */
    public boolean dualApprovalRequired(final Batch batch, final Document document,
                                        final List<Span> spans, final User reviewer) {
        if (batch == null) return false;
        for (ApprovalRuleSet set : batch.effectiveRuleSets()) {
            if (allRulesMatch(set, document, spans, reviewer)) return true;
        }
        return false;
    }

    /**
     * Returns the number of approvals this document needs in its current state. Used by the
     * queue display, where the eventual reviewer is unknown — the reviewer-experience rule is
     * therefore treated as potentially-true (worst case), so the count surfaces 2 whenever any
     * rule set could plausibly require dual approval.
     */
    public int approvalsRequired(final Batch batch, final Document document, final List<Span> spans) {
        if (batch == null) return 1;
        for (ApprovalRuleSet set : batch.effectiveRuleSets()) {
            if (allRulesPossiblyMatch(set, document, spans)) return 2;
        }
        return 1;
    }

    private static boolean allRulesMatch(final ApprovalRuleSet set, final Document document,
                                         final List<Span> spans, final User reviewer) {
        if (set == null || set.getRules() == null || set.getRules().isEmpty()) return false;
        for (String rule : set.getRules()) {
            if (!evaluate(rule, set, document, spans, reviewer)) return false;
        }
        return true;
    }

    private static boolean allRulesPossiblyMatch(final ApprovalRuleSet set, final Document document,
                                                 final List<Span> spans) {
        if (set == null || set.getRules() == null || set.getRules().isEmpty()) return false;
        for (String rule : set.getRules()) {
            if (ApprovalRule.INEXPERIENCED_REVIEWER.equals(rule)) continue; // worst-case: always satisfiable
            if (!evaluate(rule, set, document, spans, null)) return false;
        }
        return true;
    }

    private static boolean evaluate(final String rule, final ApprovalRuleSet set,
                                    final Document document, final List<Span> spans,
                                    final User reviewer) {
        switch (rule) {
            case ApprovalRule.SENSITIVE_PII:
                if (spans == null) return false;
                for (Span s : spans) {
                    if (s.getType() != null
                            && ApprovalRule.SENSITIVE_PII_TYPES.contains(s.getType().toLowerCase())) {
                        return true;
                    }
                }
                return false;
            case ApprovalRule.HIGH_RISK_SCORE:
                final double riskCutoff = set == null
                        ? ApprovalRule.HIGH_RISK_THRESHOLD : set.getRiskScoreThreshold();
                return document != null && document.getRiskScore() > riskCutoff;
            case ApprovalRule.REJECTED_HIGH_CONFIDENCE:
                if (spans == null) return false;
                final double confCutoff = set == null
                        ? ApprovalRule.HIGH_CONFIDENCE_THRESHOLD
                        : set.getRejectedConfidenceThreshold();
                for (Span s : spans) {
                    if ("REJECTED".equals(s.getStatus()) && s.getConfidence() > confCutoff) {
                        return true;
                    }
                }
                return false;
            case ApprovalRule.INEXPERIENCED_REVIEWER:
                final long expCutoff = set == null
                        ? ApprovalRule.EXPERIENCED_REVIEWER_REVIEWS
                        : set.getExperiencedReviewerThreshold();
                return reviewer == null || reviewer.getReviewCount() < expCutoff;
            case ApprovalRule.MANUAL_SPANS_THRESHOLD:
                if (spans == null) return false;
                final long manualCutoff = set == null
                        ? ApprovalRule.DEFAULT_MANUAL_SPANS_THRESHOLD
                        : set.getManualSpansThreshold();
                long manual = 0;
                for (Span s : spans) {
                    if (s.isManuallyCreated()) manual++;
                }
                return manual > manualCutoff;
            case ApprovalRule.CLASSIFIED_KEYWORDS:
                if (document == null || document.getOriginalText() == null) return false;
                final java.util.List<String> keywords = set == null
                        ? ApprovalRule.DEFAULT_CLASSIFIED_KEYWORDS
                        : set.getClassifiedKeywords();
                if (keywords == null || keywords.isEmpty()) return false;
                final String haystack = document.getOriginalText().toLowerCase();
                for (String kw : keywords) {
                    if (kw == null || kw.isBlank()) continue;
                    if (haystack.contains(kw.trim().toLowerCase())) return true;
                }
                return false;
            case ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE:
                final double samplingRate = set == null
                        ? ApprovalRule.DEFAULT_DUAL_APPROVAL_SAMPLING_RATE
                        : set.getDualApprovalSamplingRate();
                if (samplingRate <= 0.0) return false;
                if (samplingRate >= 1.0) return true;
                final Double roll = document == null ? null : document.getDualApprovalSamplingRoll();
                // Documents persisted before the sampling roll existed are conservatively
                // treated as not sampled in.
                return roll != null && roll < samplingRate;
            default:
                // Unknown rule names fail closed.
                return false;
        }
    }
}
