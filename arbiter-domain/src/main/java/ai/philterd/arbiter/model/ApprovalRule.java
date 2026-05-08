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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The set of conditions a batch may require for dual approval. Each rule has a stable
 * identifier (persisted on the {@link Batch}) and a human-readable label for the admin UI.
 * When a batch enables a rule, that rule must evaluate true for the document/reviewer at
 * approve time; if every enabled rule is true, the document needs a second approval from a
 * different reviewer before it transitions to {@code APPROVED}.
 */
public final class ApprovalRule {

    public static final String SENSITIVE_PII = "SENSITIVE_PII";
    public static final String HIGH_RISK_SCORE = "HIGH_RISK_SCORE";
    public static final String REJECTED_HIGH_CONFIDENCE = "REJECTED_HIGH_CONFIDENCE";
    public static final String INEXPERIENCED_REVIEWER = "INEXPERIENCED_REVIEWER";
    public static final String MANUAL_SPANS_THRESHOLD = "MANUAL_SPANS_THRESHOLD";
    public static final String CLASSIFIED_KEYWORDS = "CLASSIFIED_KEYWORDS";
    public static final String DUAL_APPROVAL_SAMPLING_RATE = "DUAL_APPROVAL_SAMPLING_RATE";

    /** Defaults for each numeric rule. Effective values are configured per rule set on a batch. */
    public static final double HIGH_RISK_THRESHOLD = 0.9;
    public static final double HIGH_CONFIDENCE_THRESHOLD = 0.95;
    public static final long EXPERIENCED_REVIEWER_REVIEWS = 100L;
    public static final long DEFAULT_MANUAL_SPANS_THRESHOLD = 5L;
    public static final double DEFAULT_DUAL_APPROVAL_SAMPLING_RATE = 0.02;

    public static final Set<String> SENSITIVE_PII_TYPES =
            Set.of("ssn", "credit-card", "passport-number");
    public static final java.util.List<String> DEFAULT_CLASSIFIED_KEYWORDS =
            java.util.List.of("Classified", "Proprietary", "Secret");

    /** Stable order, label per rule — used to drive the admin form. */
    public static Map<String, String> labels() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put(SENSITIVE_PII,
                "Document contains an SSN, credit card, or passport number");
        m.put(HIGH_RISK_SCORE,
                "Document risk score is greater than the configured threshold");
        m.put(REJECTED_HIGH_CONFIDENCE,
                "Reviewer rejected a PII span with confidence above the configured threshold");
        m.put(INEXPERIENCED_REVIEWER,
                "Approving reviewer has performed fewer reviews than the configured threshold");
        m.put(MANUAL_SPANS_THRESHOLD,
                "Reviewer has manually added more than the configured number of redactions");
        m.put(CLASSIFIED_KEYWORDS,
                "Document contains a classified-marker keyword");
        m.put(DUAL_APPROVAL_SAMPLING_RATE,
                "Document is randomly sampled for dual approval (audit of reviewer performance)");
        return m;
    }

    public static boolean isValid(final String name) {
        return labels().containsKey(name);
    }

    private ApprovalRule() {
    }
}
