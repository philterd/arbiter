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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Revokes the person's Philter session key ({@code DELETE /api/api-keys/current}) when they sign out of
 * Arbiter. A failure is logged and sign-out goes ahead; the key then ends at Philter's idle timeout.
 */
public class PhilterSignOutHandler implements LogoutHandler {

    private final PhilterSignInFlow flow;

    public PhilterSignOutHandler(final PhilterSignInFlow flow) {
        this.flow = flow;
    }

    @Override
    public void logout(final HttpServletRequest request, final HttpServletResponse response,
                       final Authentication authentication) {
        if (authentication instanceof PhilterSessionAuthentication philter) {
            flow.signOutQuietly(philter.getEncryptedSessionKey());
        }
    }
}
