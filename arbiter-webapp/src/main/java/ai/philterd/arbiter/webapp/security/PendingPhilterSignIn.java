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

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * A sign-in through Philter that needs one more step before the person is signed in to Arbiter: an MFA
 * code, a password change, or MFA enrollment. Held in the session. {@code encryptedSecret} is the MFA
 * challenge, or for the other two the session key Philter limits to that step.
 */
public record PendingPhilterSignIn(Step step, String username, String encryptedSecret, Instant createdAt)
        implements Serializable {

    public static final String SESSION_ATTRIBUTE = "PENDING_PHILTER_SIGN_IN";

    /** Longer than Philter's five-minute MFA challenge, so Philter decides when a challenge has expired. */
    static final Duration MAX_AGE = Duration.ofMinutes(10);

    public enum Step {
        MFA("/sign-in/mfa"),
        PASSWORD_CHANGE("/sign-in/password"),
        MFA_ENROLLMENT("/sign-in/mfa-enrollment");

        private final String path;

        Step(final String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    public static PendingPhilterSignIn of(final Step step, final String username, final String encryptedSecret) {
        return new PendingPhilterSignIn(step, username, encryptedSecret, Instant.now());
    }

    /** The pending step in the session if it is the one asked for and not too old; otherwise null. */
    public static PendingPhilterSignIn from(final HttpSession session, final Step step) {
        if (session == null) {
            return null;
        }
        final Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(value instanceof PendingPhilterSignIn pending) || pending.step() != step) {
            return null;
        }
        if (pending.createdAt().plus(MAX_AGE).isBefore(Instant.now())) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return null;
        }
        return pending;
    }

    public static void clear(final HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }
}
