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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LoginAttemptService} covering the lock threshold, lock-window
 * decay, per-IP scoping, the admin {@code unlock(email)} override, and the email-wide
 * "is this user currently locked out from anywhere?" probe used by the admin UI.
 */
class LoginAttemptServiceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-08T12:00:00Z"));
    private final Clock fixed = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginAttemptService svc = new LoginAttemptService(fixed);

    private void advance(final Duration d) {
        now.set(now.get().plus(d));
    }

    // ---------- baseline ----------

    @Test
    void brandNewKeyIsNotLocked() {
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertFalse(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void blankEmailIsNeverLocked() {
        // Defensive: the lock service must not key on an empty string and accidentally
        // lock everyone whose login form was submitted with a missing email.
        for (int i = 0; i < 10; i++) svc.onFailure("", "1.1.1.1");
        assertFalse(svc.isLocked("", "1.1.1.1"));
        assertFalse(svc.isLocked(null, "1.1.1.1"));
    }

    // ---------- threshold ----------

    @Test
    void underThresholdDoesNotLock() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
    }

    @Test
    void exactlyMaxFailuresLocks() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertTrue(svc.isEmailLocked("alice@x.com"));
    }

    // ---------- lock window ----------

    @Test
    void lockExpiresAfterWindow() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));

        advance(LoginAttemptService.LOCK_DURATION.plusSeconds(1));
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertFalse(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void postExpiryFailureResetsAndRequiresFullThresholdAgain() {
        // Trigger the lock, wait it out, then a single new failure must NOT immediately
        // re-lock the user — the counter should have reset on the post-expiry failure.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        advance(LoginAttemptService.LOCK_DURATION.plusSeconds(1));
        svc.onFailure("alice@x.com", "1.1.1.1");
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));

        // Adding (MAX-1) more in the new window finally re-locks.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));
    }

    // ---------- success clears ----------

    @Test
    void successClearsCounter() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        svc.onSuccess("alice@x.com", "1.1.1.1");

        // Fresh start: one more failure must NOT lock — confirms the prior 4 are gone.
        svc.onFailure("alice@x.com", "1.1.1.1");
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
    }

    // ---------- per-IP scoping ----------

    @Test
    void lockScopedByIpDoesNotAffectAnotherIp() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));
        // Same user from a different IP is still allowed to try.
        assertFalse(svc.isLocked("alice@x.com", "2.2.2.2"));
        // ...but isEmailLocked is per-user and *should* report locked because at least
        // one (email, ip) pair is locked — admin UI uses this to surface the badge.
        assertTrue(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void emailComparisonIsCaseInsensitive() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("Alice@X.com", "1.1.1.1");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertTrue(svc.isEmailLocked("ALICE@X.COM"));
    }

    // ---------- admin override ----------

    @Test
    void adminUnlockClearsAllIpsForEmail() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
            svc.onFailure("alice@x.com", "2.2.2.2");
        }
        assertTrue(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertTrue(svc.isLocked("alice@x.com", "2.2.2.2"));

        svc.unlock("alice@x.com");

        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertFalse(svc.isLocked("alice@x.com", "2.2.2.2"));
        assertFalse(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void unlockDoesNotAffectOtherEmails() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "1.1.1.1");
            svc.onFailure("bob@x.com", "1.1.1.1");
        }
        svc.unlock("alice@x.com");
        assertFalse(svc.isLocked("alice@x.com", "1.1.1.1"));
        assertTrue(svc.isLocked("bob@x.com", "1.1.1.1"));
    }

    @Test
    void constantsAreSensibleDefaults() {
        // Lock the formal contract: 5 failures, 15-minute window.
        assertEquals(5, LoginAttemptService.MAX_FAILURES);
        assertEquals(Duration.ofMinutes(15), LoginAttemptService.LOCK_DURATION);
        // Per-email aggregate cap: 50 failures, 1-hour window.
        assertEquals(50, LoginAttemptService.EMAIL_MAX_FAILURES);
        assertEquals(Duration.ofHours(1), LoginAttemptService.EMAIL_LOCK_DURATION);
    }

    // ---------- per-email aggregate (distributed-attack defense) ----------

    @Test
    void distributedFailuresAcrossManyIpsEventuallyLockEmailGlobally() {
        // Simulate an attacker rotating source IPs to stay under the (email, ip) threshold.
        // Use 4 failures per IP — below MAX_FAILURES — so no individual pair ever locks,
        // but the per-email aggregate still trips at EMAIL_MAX_FAILURES.
        final int perIp = LoginAttemptService.MAX_FAILURES - 1;
        final int ipsNeeded = (LoginAttemptService.EMAIL_MAX_FAILURES + perIp - 1) / perIp;
        for (int i = 0; i < ipsNeeded; i++) {
            final String ip = "10.0.0." + i;
            for (int j = 0; j < perIp; j++) {
                svc.onFailure("alice@x.com", ip);
            }
            // No individual (email, ip) pair ever crossed MAX_FAILURES.
            assertFalse(svc.isLocked("alice@x.com", ip) && svc.isEmailLocked("alice@x.com") == false,
                    "per-(email,ip) lock must not fire below MAX_FAILURES");
        }
        // A fresh IP that has never tried before is still blocked by the global cap.
        assertTrue(svc.isLocked("alice@x.com", "203.0.113.99"));
        assertTrue(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void emailGlobalLockExpiresAfterEmailLockDuration() {
        for (int i = 0; i < LoginAttemptService.EMAIL_MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "10.0.0." + i);
        }
        assertTrue(svc.isLocked("alice@x.com", "203.0.113.99"));
        advance(LoginAttemptService.EMAIL_LOCK_DURATION.plusSeconds(1));
        assertFalse(svc.isLocked("alice@x.com", "203.0.113.99"));
        assertFalse(svc.isEmailLocked("alice@x.com"));
    }

    @Test
    void successClearsEmailAggregate() {
        for (int i = 0; i < LoginAttemptService.EMAIL_MAX_FAILURES - 1; i++) {
            svc.onFailure("alice@x.com", "10.0.0." + i);
        }
        svc.onSuccess("alice@x.com", "10.0.0.1");
        // After a successful login, one more failure must NOT immediately trip the global cap.
        svc.onFailure("alice@x.com", "203.0.113.99");
        assertFalse(svc.isLocked("alice@x.com", "203.0.113.99"));
    }

    @Test
    void adminUnlockClearsEmailAggregate() {
        for (int i = 0; i < LoginAttemptService.EMAIL_MAX_FAILURES; i++) {
            svc.onFailure("alice@x.com", "10.0.0." + i);
        }
        assertTrue(svc.isEmailLocked("alice@x.com"));
        svc.unlock("alice@x.com");
        assertFalse(svc.isEmailLocked("alice@x.com"));
        assertFalse(svc.isLocked("alice@x.com", "203.0.113.99"));
    }
}
