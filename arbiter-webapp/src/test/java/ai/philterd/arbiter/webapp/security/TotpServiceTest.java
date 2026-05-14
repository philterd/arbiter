/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TotpService#verifyAndReturnStep(String, String)} — the step-aware
 * verify added for R2-F11 (TOTP replay protection within the validity window).
 *
 * <p>The samstevens library accepts a code anywhere in the ±1 step window with no
 * internal tracking, so the only way to refuse re-use is to know which step
 * accepted the code. Returning the step lets the controller compare it against
 * {@code User.lastTotpStep} and reject monotonic-decrease.
 */
class TotpServiceTest {

    /** A real 32-char base32 secret (the generator's output shape). */
    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    /** Standard RFC 6238 step size. */
    private static final int STEP_SECONDS = 30;

    /** Mutable clock so each test can drive forward in time without sleeping. */
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-05-13T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final TotpService service = new TotpService(clock);

    private long currentStep() {
        return now.get().getEpochSecond() / STEP_SECONDS;
    }

    private String codeFor(final long step) throws CodeGenerationException {
        return new DefaultCodeGenerator().generate(SECRET, step);
    }

    // ---------- happy path ----------

    @Test
    void acceptsCurrentStepCode() throws Exception {
        final long step = currentStep();
        final OptionalLong result = service.verifyAndReturnStep(SECRET, codeFor(step));
        assertTrue(result.isPresent(), "current-step code must verify");
        assertEquals(step, result.getAsLong());
    }

    @Test
    void acceptsCodeFromPreviousStepWithinWindow() throws Exception {
        // Clock skew tolerance: a code generated one step ago is still valid.
        final long prev = currentStep() - 1;
        final OptionalLong result = service.verifyAndReturnStep(SECRET, codeFor(prev));
        assertTrue(result.isPresent());
        assertEquals(prev, result.getAsLong(),
                "the returned step is the one that matched, not the current step");
    }

    @Test
    void acceptsCodeFromNextStepWithinWindow() throws Exception {
        final long next = currentStep() + 1;
        final OptionalLong result = service.verifyAndReturnStep(SECRET, codeFor(next));
        assertTrue(result.isPresent());
        assertEquals(next, result.getAsLong());
    }

    @Test
    void rejectsCodeOutsideTheWindow() throws Exception {
        // ±2 steps is beyond the documented tolerance. Reject.
        final long stale = currentStep() - 2;
        final OptionalLong result = service.verifyAndReturnStep(SECRET, codeFor(stale));
        assertFalse(result.isPresent(),
                "code beyond ±1 step must be refused — that's the clock-skew contract");
    }

    @Test
    void returnedStepAdvancesAsTimeMoves() throws Exception {
        // The step is wall-clock-derived, not call-derived. Confirm that
        // advancing the clock changes the matched step accordingly.
        final long initial = currentStep();
        assertEquals(initial,
                service.verifyAndReturnStep(SECRET, codeFor(initial)).getAsLong());

        // Move 60 seconds forward → two steps later. The same OLD code is no
        // longer accepted (out of window), but a fresh code for the new step is.
        now.set(now.get().plusSeconds(60));
        assertFalse(service.verifyAndReturnStep(SECRET, codeFor(initial)).isPresent(),
                "the previously-accepted code is now outside the window");
        assertEquals(initial + 2,
                service.verifyAndReturnStep(SECRET, codeFor(initial + 2)).getAsLong());
    }

    // ---------- malformed input ----------

    @Test
    void rejectsNullAndBlankInputs() {
        assertFalse(service.verifyAndReturnStep(null, "123456").isPresent());
        assertFalse(service.verifyAndReturnStep(SECRET, null).isPresent());
        assertFalse(service.verifyAndReturnStep(SECRET, "").isPresent());
        assertFalse(service.verifyAndReturnStep(SECRET, "   ").isPresent());
    }

    @Test
    void rejectsObviouslyWrongCode() throws Exception {
        // A 6-digit code that's definitely not the right one for any step in the window.
        // Even a single off-by-one digit must not match.
        final long step = currentStep();
        final String correct = codeFor(step);
        final String tampered = tamperOneDigit(correct);
        assertFalse(service.verifyAndReturnStep(SECRET, tampered).isPresent(),
                "single-digit tamper must not verify; got matching step for: " + tampered);
    }

    private static String tamperOneDigit(final String code) {
        final char first = code.charAt(0);
        final char swapped = first == '0' ? '1' : (char) (first - 1);
        return swapped + code.substring(1);
    }

    // ---------- legacy verify() wrapper ----------

    @Test
    void legacyVerifyDelegatesToStepResult() throws Exception {
        // Callers that don't care about replay protection still use the boolean
        // wrapper; assert it returns the same accept/reject decision.
        final long step = currentStep();
        assertTrue(service.verify(SECRET, codeFor(step)));
        assertFalse(service.verify(SECRET, codeFor(step - 5)));
        assertFalse(service.verify(SECRET, "000000"));
    }
}
