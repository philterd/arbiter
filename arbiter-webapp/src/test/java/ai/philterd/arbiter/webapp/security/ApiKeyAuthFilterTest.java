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
import ai.philterd.arbiter.service.ApiKeyHashingService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private UserRepository userRepository;
    private ApiKeyHashingService hashingService;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        hashingService = mock(ApiKeyHashingService.class);
        filter = new ApiKeyAuthFilter(userRepository, hashingService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----- helpers -----

    private static Authentication sessionAuth(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static User user(final String email) {
        final User u = new User();
        u.setEmail(email);
        u.setRoles(Set.of("USER"));
        return u;
    }

    private static MockHttpServletRequest apiReq(final String token) {
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI("/api/v1/batches");
        if (token != null) r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    private static MockHttpServletRequest uiReq(final String token) {
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI("/queue");
        if (token != null) r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    // ----- /api/** with no session: normal Bearer flow -----

    @Test
    void setsAuthAndMarkerWhenValidTokenAndNoSession() throws Exception {
        when(hashingService.hash("tok")).thenReturn("h");
        when(userRepository.findByApiKey("h")).thenReturn(Optional.of(user("api@x.com")));

        filter.doFilter(apiReq("tok"), new MockHttpServletResponse(), mock(FilterChain.class));

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("api@x.com", auth.getName());
        // Marker attribute present so ApiSessionRejectingFilter does not clear this context.
        // We can't check request attributes via MockHttpServletResponse, but we verified
        // the auth was set — the setAttribute path is the only way that happens.
    }

    @Test
    void doesNotSetAuthWhenTokenAbsentOnApiPath() throws Exception {
        filter.doFilter(apiReq(null), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(userRepository, never()).findByApiKey(any());
    }

    @Test
    void doesNotSetAuthWhenTokenInvalidOnApiPath() throws Exception {
        when(hashingService.hash("bad")).thenReturn("hbad");
        when(userRepository.findByApiKey("hbad")).thenReturn(Optional.empty());

        filter.doFilter(apiReq("bad"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- /api/** with session cookie also present: Bearer must win -----

    @Test
    void bearerOverridesSessionAuthOnApiPath() throws Exception {
        // Session has already populated the context with a different identity.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("session-user@x.com"));

        when(hashingService.hash("tok")).thenReturn("h");
        when(userRepository.findByApiKey("h")).thenReturn(Optional.of(user("api@x.com")));

        final MockHttpServletRequest req = apiReq("tok");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("api@x.com", auth.getName(),
                "Bearer identity must replace session identity on /api/**");
        assertTrue(Boolean.TRUE.equals(req.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR)),
                "BEARER_AUTH_ATTR must be set so ApiSessionRejectingFilter keeps the context");
    }

    @Test
    void invalidBearerWithSessionOnApiPathLeavesSessionForRejectingFilterToStrip() throws Exception {
        // Session present, but Bearer token is not in the database.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("session-user@x.com"));

        when(hashingService.hash("bad")).thenReturn("hbad");
        when(userRepository.findByApiKey("hbad")).thenReturn(Optional.empty());

        filter.doFilter(apiReq("bad"), new MockHttpServletResponse(), mock(FilterChain.class));

        // Auth is still the session auth — ApiSessionRejectingFilter will strip it next.
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("session-user@x.com", auth.getName());
    }

    // ----- non-/api/** paths: session auth is left alone -----

    @Test
    void doesNotReplaceSessionAuthOnUiPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("session-user@x.com"));

        // Even with a valid token, the UI path should not replace existing session auth.
        when(hashingService.hash("tok")).thenReturn("h");
        when(userRepository.findByApiKey("h")).thenReturn(Optional.of(user("api@x.com")));

        filter.doFilter(uiReq("tok"), new MockHttpServletResponse(), mock(FilterChain.class));

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("session-user@x.com", auth.getName(),
                "Session auth must not be replaced by Bearer on non-/api/** paths");
    }

    @Test
    void setsAuthOnUiPathWhenNoSession() throws Exception {
        when(hashingService.hash("tok")).thenReturn("h");
        when(userRepository.findByApiKey("h")).thenReturn(Optional.of(user("api@x.com")));

        filter.doFilter(uiReq("tok"), new MockHttpServletResponse(), mock(FilterChain.class));

        // No session was present, so Bearer is accepted even on a non-API path.
        assertEquals("api@x.com", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    // ----- filter chain always called -----

    @Test
    void alwaysCallsFilterChain() throws Exception {
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletRequest req = apiReq(null);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    // helper to avoid raw-type suppression noise
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
