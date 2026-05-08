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

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory failed-authentication counter for the password and MFA stages of login.
 * Counters are keyed by {@code (email, ip)} so a single attacker can't lock out a real
 * user by spamming bad guesses from one IP, while the same user account remains usable
 * from a different IP.
 *
 * <p>After {@value #MAX_FAILURES} failures the (email, ip) pair is locked for
 * {@link #LOCK_DURATION}. Failures past the lock window reset the counter; admins can
 * also force an unlock via {@link #unlock(String)} from the user-admin page.
 *
 * <p>Storage is process-local — sufficient for single-instance deployments. Multi-instance
 * deployments should redirect this to a shared store (Redis / Valkey / Mongo) by replacing
 * this bean with one that persists {@link AttemptState}.
 */
@Service
public class LoginAttemptService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    public LoginAttemptService(final Clock clock) {
        this.clock = clock;
    }

    /** True when the (email, ip) pair is currently locked out. */
    public boolean isLocked(final String email, final String ip) {
        if (email == null || email.isBlank()) return false;
        final AttemptState state = attempts.get(key(email, ip));
        if (state == null || state.lockedUntil == null) return false;
        return clock.instant().isBefore(state.lockedUntil);
    }

    /**
     * Record a failed authentication attempt. After {@link #MAX_FAILURES} consecutive failures
     * within the lock window, the (email, ip) pair becomes locked for {@link #LOCK_DURATION}.
     * If the lock has already expired by the time this is called, the counter resets first.
     */
    public void onFailure(final String email, final String ip) {
        if (email == null || email.isBlank()) return;
        final Instant now = clock.instant();
        attempts.compute(key(email, ip), (k, prev) -> {
            AttemptState state = prev == null ? new AttemptState() : prev;
            // Lock window has elapsed — reset before incrementing so old failures don't
            // count against a returning user.
            if (state.lockedUntil != null && !now.isBefore(state.lockedUntil)) {
                state = new AttemptState();
            }
            state.failures += 1;
            if (state.failures >= MAX_FAILURES) {
                state.lockedUntil = now.plus(LOCK_DURATION);
            }
            return state;
        });
    }

    /** Clear any failure record for the (email, ip) pair after a successful authentication. */
    public void onSuccess(final String email, final String ip) {
        if (email == null || email.isBlank()) return;
        attempts.remove(key(email, ip));
    }

    /**
     * Admin override: drop every failure record for the email regardless of source IP.
     * The next attempt from any IP starts at zero failures.
     */
    public void unlock(final String email) {
        if (email == null || email.isBlank()) return;
        final String prefix = email.toLowerCase(Locale.ROOT) + "|";
        attempts.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * True when at least one (email, ip) pair for this email is currently locked. Used to
     * surface a "locked" badge + Unlock button in the admin user list.
     */
    public boolean isEmailLocked(final String email) {
        if (email == null || email.isBlank()) return false;
        final String prefix = email.toLowerCase(Locale.ROOT) + "|";
        final Instant now = clock.instant();
        for (Map.Entry<String, AttemptState> e : attempts.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            final AttemptState s = e.getValue();
            if (s.lockedUntil != null && now.isBefore(s.lockedUntil)) return true;
        }
        return false;
    }

    private static String key(final String email, final String ip) {
        return email.toLowerCase(Locale.ROOT) + "|" + (ip == null ? "" : ip);
    }

    private static final class AttemptState {
        int failures;
        Instant lockedUntil;
    }
}
