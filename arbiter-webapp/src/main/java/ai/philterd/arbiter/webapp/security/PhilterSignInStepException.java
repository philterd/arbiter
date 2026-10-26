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

import org.springframework.security.core.AuthenticationException;

/**
 * Philter accepted the password but the person has another step to complete before they are signed in.
 * {@link PhilterSignInFailureHandler} stores the step in the session and sends the person to its page.
 */
public class PhilterSignInStepException extends AuthenticationException {

    private final transient PendingPhilterSignIn pending;

    public PhilterSignInStepException(final PendingPhilterSignIn pending) {
        super("Another sign-in step is required.");
        this.pending = pending;
    }

    public PendingPhilterSignIn getPending() {
        return pending;
    }
}
