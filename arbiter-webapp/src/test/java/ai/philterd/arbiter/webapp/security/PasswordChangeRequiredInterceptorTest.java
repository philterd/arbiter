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

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the rules of {@link PasswordChangeRequiredInterceptor}: any flagged
 * user is bounced to {@code /settings} until they rotate, except for the
 * password-change endpoint and the small allow-list of paths that need to keep
 * working (logout, MFA, login, static assets).
 */
class PasswordChangeRequiredInterceptorTest {

    private UserRepository userRepository;
    private PasswordChangeRequiredInterceptor interceptor;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        interceptor = new PasswordChangeRequiredInterceptor(userRepository);
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

    private static User user(final boolean mustChange) {
        final User u = new User();
        u.setEmail("alice@x.com");
        u.setMustChangePassword(mustChange);
        return u;
    }

    private static HttpServletRequest req(final String path) {
        final HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(path);
        when(r.getContextPath()).thenReturn("");
        return r;
    }

    @Test
    void redirectsFlaggedUserOffOfArbitraryPathsToSettings() throws Exception {
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(true)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/queue"), resp, new Object());

        assertFalse(cont, "flagged user must not be allowed to proceed to /queue");
        verify(resp).sendRedirect(contains("/settings"));
    }

    @Test
    void allowsFlaggedUserToReachSettings() throws Exception {
        // The user has to be able to see the form to fix the situation.
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(true)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/settings"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }

    @Test
    void allowsFlaggedUserToPostToChangePasswordEndpoint() throws Exception {
        // /settings/password is the *only* write that can clear the flag, so it
        // has to remain reachable while the gate is active.
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(true)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/settings/password"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }

    @Test
    void allowsFlaggedUserToLogout() throws Exception {
        // A user who decides not to proceed has to be able to sign out without
        // first being able to do anything else.
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(true)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/logout"), resp, new Object());

        assertTrue(cont);
    }

    @Test
    void doesNotInterfereWithUnflaggedUser() throws Exception {
        authenticate("alice@x.com");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user(false)));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/queue"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }

    @Test
    void doesNotRedirectAnonymousRequest() throws Exception {
        // Login pages and other unauthenticated traffic must not get redirected
        // to /settings (which would be an authenticated-only page).
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("k", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        final HttpServletResponse resp = mock(HttpServletResponse.class);

        final boolean cont = interceptor.preHandle(req("/login"), resp, new Object());

        assertTrue(cont);
        verify(resp, never()).sendRedirect(any());
    }
}
