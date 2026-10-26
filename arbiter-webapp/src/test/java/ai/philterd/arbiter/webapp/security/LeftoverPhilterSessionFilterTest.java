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

import ai.philterd.arbiter.service.AuditLogService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LeftoverPhilterSessionFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void philterSessionFromBeforeTheSwitchToLocalIsEnded() throws Exception {
        final AuditLogService audit = mock(AuditLogService.class);
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        final MockHttpSession session = new MockHttpSession();
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        request.setSession(session);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        new LeftoverPhilterSessionFilter(audit).doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        assertEquals("/login?expired", response.getRedirectedUrl());
        verify(chain, never()).doFilter(any(), any());
        verify(audit).logForUser(eq("jdoe"), eq("LOGOUT"), eq("User"), eq(null),
                eq(AuditLogService.OUTCOME_SUCCESS), eq(Map.of("reason", "sign_in_mode_changed")));
    }

    @Test
    void localSessionsAreLeftAlone() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        final MockHttpSession session = new MockHttpSession();
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        request.setSession(session);
        final FilterChain chain = mock(FilterChain.class);

        new LeftoverPhilterSessionFilter(mock(AuditLogService.class)).doFilter(request, new MockHttpServletResponse(), chain);

        assertFalse(session.isInvalid());
        verify(chain).doFilter(eq(request), any());
    }
}
