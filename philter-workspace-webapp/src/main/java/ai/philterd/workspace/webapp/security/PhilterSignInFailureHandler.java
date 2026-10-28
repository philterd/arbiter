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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * Where the sign-in form goes when Philter does not sign the person straight in: to the page for a
 * further step, or back to the login page with a message that does not say whether the username exists.
 */
public class PhilterSignInFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final AuthenticationException exception) throws IOException {
        response.sendRedirect(request.getContextPath() + target(request, exception));
    }

    static String target(final HttpServletRequest request, final AuthenticationException exception) {
        if (exception instanceof PhilterSignInStepException step && step.getPending() != null) {
            request.getSession(true).setAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE, step.getPending());
            return step.getPending().step().path();
        }
        PendingPhilterSignIn.clear(request.getSession(false));
        if (exception instanceof LockedException) {
            return "/login?locked";
        }
        if (exception instanceof UnsupportedPhilterException) {
            return "/login?unsupported";
        }
        if (exception instanceof AuthenticationServiceException) {
            return "/login?unavailable";
        }
        return "/login?error";
    }
}
