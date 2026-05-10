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

import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Boundary tests for the Blind Double Review selection that happens during redaction
 * persistence. The probabilistic case (e.g. 50%) is hard to assert deterministically, so
 * the tests instead pin the deterministic boundaries: feature off, 0%, 100%, already
 * selected, and out-of-range percentages. The 50% case is asserted statistically across a
 * large sample where the binomial confidence interval is very tight.
 */
class BlindDoubleReviewSelectionTest {

    private RedactionPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new RedactionPersistenceService(
                mock(DocumentRepository.class),
                mock(SpanRepository.class),
                mock(OpenSearchIndexService.class));
    }

    @Test
    void featureDisabledNeverSelectsRegardlessOfPercentage() {
        final Batch batch = batch(false, 100);
        for (int i = 0; i < 200; i++) {
            final Document d = service.apply(newDocument(), batch, response("hello"));
            assertFalse(d.isDoubleReview(),
                    "Documents must never be flagged when Blind Double Review is disabled, "
                            + "even when the stored percentage is 100.");
        }
    }

    @Test
    void hundredPercentAlwaysSelects() {
        // ThreadLocalRandom.nextDouble() is in [0.0, 1.0), so 1.0 < 1.0 is false. Guard
        // against a regression that uses <= or that fails to round to 1.0 — the test runs
        // many trials so a one-in-a-million miss would still surface deterministically.
        final Batch batch = batch(true, 100);
        for (int i = 0; i < 500; i++) {
            final Document d = service.apply(newDocument(), batch, response("hello"));
            assertTrue(d.isDoubleReview(),
                    "Every document must be selected when the percentage is 100.");
        }
    }

    @Test
    void zeroPercentNeverSelects() {
        final Batch batch = batch(true, 0);
        for (int i = 0; i < 500; i++) {
            final Document d = service.apply(newDocument(), batch, response("hello"));
            assertFalse(d.isDoubleReview(),
                    "No document must be selected when the percentage is 0.");
        }
    }

    @Test
    void aDocumentAlreadyMarkedRetainsItsSelectionOnReRedaction() {
        // Re-running redaction on a doc that was already flagged must not flip the flag,
        // so the cohort of double-review documents is stable for the lifetime of the batch.
        // The batch is set to 0% to prove the existing flag is preserved rather than reset.
        final Batch batch = batch(true, 0);
        final Document d = newDocument();
        d.setDoubleReview(true);
        service.apply(d, batch, response("hello"));
        assertTrue(d.isDoubleReview(),
                "An existing doubleReview=true must not be cleared even when the rolled probability is 0.");
    }

    @Test
    void aDocumentNotPreviouslyMarkedIsNotForciblySelected() {
        // The flip side: a document that was not already flagged stays unflagged when the
        // configured probability is 0, even if it's run through redaction multiple times.
        final Batch batch = batch(true, 0);
        final Document d = newDocument();
        for (int i = 0; i < 50; i++) {
            service.apply(d, batch, response("hello"));
        }
        assertFalse(d.isDoubleReview(),
                "Re-running redaction at 0% must keep the document unflagged.");
    }

    @Test
    void negativePercentageIsClampedAndSelectsNothing() {
        // Math.max(0, percentage) / 100.0 — a negative stored value (legacy or corrupt
        // data) must not crash and must not select.
        final Batch batch = batch(true, -25);
        for (int i = 0; i < 200; i++) {
            final Document d = service.apply(newDocument(), batch, response("hello"));
            assertFalse(d.isDoubleReview(),
                    "Negative percentages must be clamped to 0 — no document is selected.");
        }
    }

    @Test
    void overOneHundredPercentageIsClampedAndAlwaysSelects() {
        // Math.min(100, percentage) — a stored value above 100 must clamp to 100 rather
        // than overflow probability arithmetic.
        final Batch batch = batch(true, 250);
        for (int i = 0; i < 200; i++) {
            final Document d = service.apply(newDocument(), batch, response("hello"));
            assertTrue(d.isDoubleReview(),
                    "Percentages above 100 must clamp to 100 — every document is selected.");
        }
    }

    @Test
    void fiftyPercentSampleRateConvergesAroundFiftyPercent() {
        // Pure statistical sanity check — over 5,000 trials at p=0.5 the observed rate
        // should land in [40%, 60%] with overwhelming probability. The bounds are loose
        // enough that flakiness from a fair RNG is essentially impossible (each tail is
        // a 100σ event), but tight enough to catch a regression that swaps p with 1-p
        // or always returns the same answer.
        final int trials = 5000;
        final Batch batch = batch(true, 50);
        int selected = 0;
        for (int i = 0; i < trials; i++) {
            if (service.apply(newDocument(), batch, response("hello")).isDoubleReview()) {
                selected++;
            }
        }
        final double rate = (double) selected / (double) trials;
        assertTrue(rate >= 0.40 && rate <= 0.60,
                "Over " + trials + " trials at p=0.5 the observed rate should be near 0.5; got "
                        + rate + " (" + selected + "/" + trials + ").");
    }

    // ---------- helpers ----------

    private static Batch batch(final boolean enabled, final int percentage) {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("test");
        b.setBlindDoubleReviewEnabled(enabled);
        b.setBlindDoubleReviewPercentage(percentage);
        return b;
    }

    private static Document newDocument() {
        final Document d = new Document();
        d.setId(UUID.randomUUID().toString());
        d.setBatchId("b1");
        return d;
    }

    private static RedactionResponse response(final String text) {
        return new RedactionResponse(text, text, List.of());
    }
}
