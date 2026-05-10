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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestStatusTest {

    @Test
    void needsReviewBeatsEverythingElse() {
        // The needsReview signal trumps the audit-sampling rate. A doc with PENDING spans
        // is never auto-approved or audit-sampled — it must land in REVIEW_REQUIRED.
        assertEquals("REVIEW_REQUIRED", IngestStatus.pick(batch(0.0), true));
        assertEquals("REVIEW_REQUIRED", IngestStatus.pick(batch(0.5), true));
        assertEquals("REVIEW_REQUIRED", IngestStatus.pick(batch(1.0), true));
        // …and even with a null batch, since needsReview short-circuits before the rate read.
        assertEquals("REVIEW_REQUIRED", IngestStatus.pick(null, true));
    }

    @Test
    void auditSamplingRateAtZeroAlwaysAutoApproves() {
        for (int i = 0; i < 1000; i++) {
            assertEquals("AUTO_APPROVED", IngestStatus.pick(batch(0.0), false));
        }
    }

    @Test
    void auditSamplingRateAtOneAlwaysAuditRequires() {
        for (int i = 0; i < 1000; i++) {
            assertEquals("AUDIT_REQUIRED", IngestStatus.pick(batch(1.0), false));
        }
    }

    @Test
    void nullBatchTreatedAsZeroSamplingRate() {
        // Defensive: a null batch must not crash and must default to AUTO_APPROVED so a
        // legacy/orphaned document doesn't accidentally route to review.
        assertEquals("AUTO_APPROVED", IngestStatus.pick(null, false));
    }

    @Test
    void negativeSamplingRateClampsToAutoApprove() {
        // The {@code <= 0} branch handles legacy data with a stored negative rate.
        assertEquals("AUTO_APPROVED", IngestStatus.pick(batch(-0.5), false));
    }

    @Test
    void samplingRateAboveOneIsTreatedAsAlwaysAuditRequired() {
        // The {@code >= 1.0} branch covers any value at or above 1.0 (e.g. legacy data
        // that stored the rate as a percentage rather than a fraction).
        assertEquals("AUDIT_REQUIRED", IngestStatus.pick(batch(1.5), false));
    }

    @Test
    void halfSamplingRateProducesBothOutcomesAcrossManyTrials() {
        // Statistical sanity check on the only stochastic branch. With p=0.5 over 5,000
        // trials, both outcomes must occur and the observed proportion must land in a
        // generous [0.40, 0.60] window — wide enough that fair-RNG flakiness is essentially
        // impossible, narrow enough to catch a regression that fixes the result.
        final int trials = 5000;
        int auditRequired = 0;
        int autoApproved = 0;
        for (int i = 0; i < trials; i++) {
            final String result = IngestStatus.pick(batch(0.5), false);
            if ("AUDIT_REQUIRED".equals(result)) auditRequired++;
            else if ("AUTO_APPROVED".equals(result)) autoApproved++;
            else throw new AssertionError("Unexpected status from pick: " + result);
        }
        assertTrue(auditRequired > 0, "p=0.5 must occasionally produce AUDIT_REQUIRED.");
        assertTrue(autoApproved > 0, "p=0.5 must occasionally produce AUTO_APPROVED.");
        final double rate = (double) auditRequired / trials;
        assertTrue(rate >= 0.40 && rate <= 0.60,
                "Observed AUDIT_REQUIRED rate at p=0.5 was " + rate
                        + "; expected within [0.40, 0.60].");
    }

    @Test
    void verySmallSamplingRateRarelyAuditRequires() {
        // A rate close to zero must still occasionally pick AUTO_APPROVED but not
        // collapse into the always-AUTO_APPROVED branch (which would happen if a
        // floating-point comparison silently rounded the rate down to zero).
        final Batch b = batch(0.05);
        int audits = 0;
        for (int i = 0; i < 10000; i++) {
            if ("AUDIT_REQUIRED".equals(IngestStatus.pick(b, false))) audits++;
        }
        // p=0.05 over 10k trials → mean 500, σ ≈ 21.8. Wide bounds avoid flakiness.
        assertTrue(audits > 250 && audits < 800,
                "Expected ~5% AUDIT_REQUIRED at p=0.05; got " + audits + " in 10000 trials.");
        // And the random branch must not collapse to a single outcome:
        assertNotEquals(0, audits);
        assertNotEquals(10000, audits);
    }

    private static Batch batch(final double samplingRate) {
        final Batch b = new Batch();
        b.setAuditSamplingRate(samplingRate);
        return b;
    }
}
