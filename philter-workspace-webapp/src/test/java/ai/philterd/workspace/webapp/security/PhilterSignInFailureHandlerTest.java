/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PhilterSignInFailureHandlerTest {

    private final PhilterSignInFailureHandler handler = new PhilterSignInFailureHandler();

    @Test
    void stepIsStoredInTheSessionAndThePersonSentToItsPage() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final PendingPhilterSignIn pending = PendingPhilterSignIn.of(PendingPhilterSignIn.Step.MFA, "jdoe", "enc");

        handler.onAuthenticationFailure(request, response, new PhilterSignInStepException(pending));

        assertEquals("/sign-in/mfa", response.getRedirectedUrl());
        assertSame(pending, request.getSession().getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void failuresGoBackToSignInAndClearAnyPendingStep() throws Exception {
        assertEquals("/login?error", redirectFor(new BadCredentialsException("x")));
        assertEquals("/login?locked", redirectFor(new LockedException("x")));
        assertEquals("/login?unavailable", redirectFor(new AuthenticationServiceException("x")));
        assertEquals("/login?unsupported", redirectFor(new UnsupportedPhilterException("x")));
    }

    private static String redirectFor(final org.springframework.security.core.AuthenticationException e)
            throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE,
                new PendingPhilterSignIn(PendingPhilterSignIn.Step.MFA, "jdoe", "enc", Instant.now()));
        request.setSession(session);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        new PhilterSignInFailureHandler().onAuthenticationFailure(request, response, e);
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
        return response.getRedirectedUrl();
    }
}
