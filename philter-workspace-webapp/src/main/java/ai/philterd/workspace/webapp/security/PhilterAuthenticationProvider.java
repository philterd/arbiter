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
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The sign-in form's provider: sends the username and password
 * to Philter's {@code POST /api/sign-in} with the person's address and user agent, so Philter rate limits
 * and lists each person's sessions separately. Philter Workspace keeps no password and does no lockout of its own;
 * Philter's lockout and rate limit apply. A Philter whose version Philter Workspace does not support is refused first
 * ({@link PhilterVersions}).
 */
public class PhilterAuthenticationProvider implements AuthenticationProvider {

    private final PhilterSignInService signInService;
    private final PhilterSignInFlow flow;

    public PhilterAuthenticationProvider(final PhilterSignInService signInService, final PhilterSignInFlow flow) {
        this.signInService = signInService;
        this.flow = flow;
    }

    @Override
    public Authentication authenticate(final Authentication authentication) throws AuthenticationException {
        final String username = authentication.getName() == null ? "" : authentication.getName().trim();
        final String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        if (username.isEmpty() || password.isEmpty()) {
            throw new BadCredentialsException("Bad credentials");
        }
        final String unsupported = PhilterVersions.unsupported(signInService);
        if (unsupported != null) {
            throw flow.unsupported(username, unsupported);
        }
        final HttpServletRequest request = currentRequest();
        try {
            return flow.complete(signInService.signIn(username, password,
                    PhilterSignInFlow.clientAddress(request), PhilterSignInFlow.userAgent(request)), username);
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw flow.refused(username, e);
        }
    }

    @Override
    public boolean supports(final Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
