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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.GeneralSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the rules of {@link MfaEnrollmentInterceptor}: when the admin has
 * required MFA, an authenticated user with no TOTP secret is bounced to the
 * setup page, except for the small allow-list of paths that need to keep
 * working. /api/** is included in the gating contract (finding #4) but Bearer
 * API-key requests are exempt.
 */
class MfaEnrollmentInterceptorTest {

    private GeneralSettingsService generalSettingsService;
    private UserRepository userRepository;
    private MfaEnrollmentInterceptor interceptor;

    @BeforeEach
    void setUp() {
        generalSettingsService = mock(GeneralSettingsService.class);
        userRepository = mock(UserRepository.class);
        interceptor = new MfaEnrollmentInterceptor(generalSettingsService, userRepository);
        // Default: require-MFA is on; tests that need it off override.
        final GeneralSettings gs = new GeneralSettings();
        gs.setRequireMfa(true);
        when(generalSettingsService.load()).thenReturn(gs);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(final String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static User user(final boolean mfaEnabled) {
        final User u = new User();
        u.setEmail("alice@x.com");
        u.setMfaEnabled(mfaEnabled);
        return u;
    }

    private static HttpServletRequest req(final String path) {
        final HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(path);
        when(r.getContextPath()).thenReturn("");
        return r;
    }

    // ---------- browser UI gating ----------

    @Test
    void redirectsUnenrolledUserOffOfArbitraryUiPathsToSetup() throws Exception {
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(false)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/queue"), resp, new Object());

        assertFalse(cont);
        verify(resp).sendRedirect(contains("/settings/mfa/setup"));
    }

    @Test
    void allowsEnrolledUserThrough() throws Exception {
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(true)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/queue"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }

    @Test
    void doesNothingWhenRequireMfaIsOff() throws Exception {
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(false)));
        final GeneralSettings gs = new GeneralSettings();
        gs.setRequireMfa(false);
        when(generalSettingsService.load()).thenReturn(gs);
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/queue"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }

    // ---------- /api/** gating (finding #4) ----------

    @Test
    void rejectsUnenrolledUserOnApiPathWithForbiddenInsteadOfRedirect() throws Exception {
        // Before finding #4: /api/** was on the exclusion list and an unenrolled
        // user could mutate via /api/v1/** while bypassing the MFA requirement.
        // Now: the gate fires, and /api/ paths get a 403 instead of a 302 (which
        // wouldn't survive a cross-origin fetch round-trip).
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(false)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/api/v1/spans/abc"), resp, new Object());

        assertFalse(cont);
        verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), contains("MFA enrollment required"));
        verify(resp, never()).sendRedirect(any());
    }

    @Test
    void bearerAuthenticatedRequestSkipsGateEvenWhenUnenrolled() throws Exception {
        // API-key callers don't go through the TOTP login flow at all, so MFA
        // enrolment requirements don't apply to them. Otherwise flipping
        // require-MFA on would lock out every automated client until a human
        // logged in and completed enrolment.
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(false)));
        final HttpServletRequest r = req("/api/v1/spans/abc");
        when(r.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR)).thenReturn(Boolean.TRUE);
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(r, resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendError(anyInt(), any());
        verify(resp, never()).sendRedirect(any());
    }
}
