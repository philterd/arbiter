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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One configured rule set on a batch. Rules within a single set are AND-ed at evaluation time;
 * a batch may carry several rule sets and they are OR-ed against each other (any set whose rules
 * all hold triggers dual approval). Each set carries its own numeric thresholds so two rule sets
 * on the same batch can use different cutoffs.
 */
public class ApprovalRuleSet {

    private String id;
    private Set<String> rules = new LinkedHashSet<>();
    private double riskScoreThreshold = 0.9;
    private double rejectedConfidenceThreshold = 0.95;
    private long experiencedReviewerThreshold = 100;
    /** Triggers when the count of manually-created spans on a document exceeds this value. */
    private long manualSpansThreshold = ApprovalRule.DEFAULT_MANUAL_SPANS_THRESHOLD;
    /** Case-insensitive substrings that, if any appear in the document text, trigger the rule. */
    private java.util.List<String> classifiedKeywords =
            new java.util.ArrayList<>(ApprovalRule.DEFAULT_CLASSIFIED_KEYWORDS);
    /** Probability (0–1) that a document is randomly sampled for dual approval. */
    private double dualApprovalSamplingRate = ApprovalRule.DEFAULT_DUAL_APPROVAL_SAMPLING_RATE;

    public ApprovalRuleSet() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public Set<String> getRules() {
        if (rules == null) rules = new LinkedHashSet<>();
        return rules;
    }
    public void setRules(final Set<String> rules) {
        this.rules = rules == null ? new LinkedHashSet<>() : rules;
    }

    public double getRiskScoreThreshold() { return riskScoreThreshold; }
    public void setRiskScoreThreshold(final double riskScoreThreshold) {
        this.riskScoreThreshold = riskScoreThreshold;
    }

    public double getRejectedConfidenceThreshold() { return rejectedConfidenceThreshold; }
    public void setRejectedConfidenceThreshold(final double rejectedConfidenceThreshold) {
        this.rejectedConfidenceThreshold = rejectedConfidenceThreshold;
    }

    public long getExperiencedReviewerThreshold() { return experiencedReviewerThreshold; }
    public void setExperiencedReviewerThreshold(final long experiencedReviewerThreshold) {
        this.experiencedReviewerThreshold = experiencedReviewerThreshold;
    }

    public long getManualSpansThreshold() { return manualSpansThreshold; }
    public void setManualSpansThreshold(final long manualSpansThreshold) {
        this.manualSpansThreshold = manualSpansThreshold;
    }

    public java.util.List<String> getClassifiedKeywords() {
        if (classifiedKeywords == null) classifiedKeywords = new java.util.ArrayList<>();
        return classifiedKeywords;
    }
    public void setClassifiedKeywords(final java.util.List<String> classifiedKeywords) {
        this.classifiedKeywords = classifiedKeywords == null
                ? new java.util.ArrayList<>() : classifiedKeywords;
    }

    public double getDualApprovalSamplingRate() { return dualApprovalSamplingRate; }
    public void setDualApprovalSamplingRate(final double dualApprovalSamplingRate) {
        this.dualApprovalSamplingRate = dualApprovalSamplingRate;
    }
}
