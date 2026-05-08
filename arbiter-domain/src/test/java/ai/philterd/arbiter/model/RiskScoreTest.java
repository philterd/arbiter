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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScoreTest {

    private static Span span(final String type, final double confidence, final String status) {
        final Span s = new Span();
        s.setType(type);
        s.setConfidence(confidence);
        s.setStatus(status);
        return s;
    }

    @Test
    void emptySpansReturnsZero() {
        assertEquals(0.0, RiskScore.compute(List.of(), "lots of words here", null));
    }

    @Test
    void nullTextReturnsZero() {
        final Span s = span("ssn", 0.5, "PENDING");
        assertEquals(0.0, RiskScore.compute(List.of(s), null, null));
    }

    @Test
    void emptyTextReturnsZero() {
        final Span s = span("ssn", 0.5, "PENDING");
        assertEquals(0.0, RiskScore.compute(List.of(s), "   ", null));
    }

    @Test
    void zeroWordsReturnsZeroEvenWithSpans() {
        // null words count short-circuits to 0
        final Span s = span("ssn", 0.5, "APPROVED");
        assertEquals(0.0, RiskScore.compute(List.of(s), "", null));
    }

    @Test
    void approvedSpanContributesWithoutPenalty() {
        // ssn weight 10, conf 0.8, doc 100 words → (10*(1-0.8) + 0) / 100 = 0.02
        final Span s = span("ssn", 0.8, "APPROVED");
        final String text = "word ".repeat(100).trim();
        final double r = RiskScore.compute(List.of(s), text, null);
        assertEquals(0.02, r, 1e-9);
    }

    @Test
    void pendingSpanAddsOnePenalty() {
        // ssn weight 10, conf 0.8, doc 100 words, PENDING → (10*(1-0.8) + 1) / 100 = 0.03
        final Span s = span("ssn", 0.8, "PENDING");
        final String text = "word ".repeat(100).trim();
        final double r = RiskScore.compute(List.of(s), text, null);
        assertEquals(0.03, r, 1e-9);
    }

    @Test
    void weightOverridesArePicked() {
        // override ssn weight to 1; conf 0.5; 100 words → (1*0.5)/100 = 0.005
        final Span s = span("ssn", 0.5, "APPROVED");
        final String text = "word ".repeat(100).trim();
        final double r = RiskScore.compute(List.of(s), text, Map.of("ssn", 1));
        assertEquals(0.005, r, 1e-9);
    }

    @Test
    void unknownTypeUsesFallbackWeightOfOne() {
        final Span s = span("flying-saucer", 0.0, "APPROVED");
        final String text = "word ".repeat(100).trim();
        final double r = RiskScore.compute(List.of(s), text, null);
        assertEquals(0.01, r, 1e-9);
    }

    @Test
    void resultClampedToOne() {
        // very high-weight, low-confidence span in a tiny document
        final Span s = span("ssn", 0.0, "PENDING"); // weight 10, full risk
        final String text = "tiny";
        final double r = RiskScore.compute(List.of(s), text, null);
        assertEquals(1.0, r, 1e-9);
    }

    @Test
    void confidenceClampedToZeroOne() {
        // confidence 1.5 should clamp to 1.0 → contribution = 0
        final Span s = span("ssn", 1.5, "APPROVED");
        final String text = "word ".repeat(100).trim();
        assertEquals(0.0, RiskScore.compute(List.of(s), text, null), 1e-9);

        // confidence -0.5 should clamp to 0.0 → full weight contribution
        final Span low = span("ssn", -0.5, "APPROVED");
        assertEquals(0.10, RiskScore.compute(List.of(low), text, null), 1e-9);
    }

    @Test
    void multipleSpansSumContributions() {
        // ssn (10, 0.8 APPROVED) + phone-number (5, 0.6 PENDING) + 100 words
        // = (10*0.2 + 5*0.4 + 1) / 100 = (2 + 2 + 1)/100 = 0.05
        final List<Span> spans = List.of(
                span("ssn", 0.8, "APPROVED"),
                span("phone-number", 0.6, "PENDING"));
        final String text = "word ".repeat(100).trim();
        assertEquals(0.05, RiskScore.compute(spans, text, null), 1e-9);
    }

    @Test
    void countWordsTrimsWhitespace() {
        assertEquals(0, RiskScore.countWords(""));
        assertEquals(0, RiskScore.countWords("   \t\n"));
        assertEquals(1, RiskScore.countWords("hello"));
        assertEquals(3, RiskScore.countWords("  one  two   three "));
        assertEquals(3, RiskScore.countWords("one\ntwo\tthree"));
    }

    @Test
    void scoreIsAlwaysWithinUnitInterval() {
        // sanity: many random-ish spans, score is in [0, 1]
        final Span a = span("ssn", 0.1, "PENDING");
        final Span b = span("credit-card", 0.2, "PENDING");
        final Span c = span("phone-number", 0.9, "APPROVED");
        final String text = "word ".repeat(50).trim();
        final double r = RiskScore.compute(List.of(a, b, c), text, null);
        assertTrue(r >= 0.0 && r <= 1.0, "expected 0..1 but got " + r);
    }
}
