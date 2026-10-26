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
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterSessionValidationFilterTest {

    private static final long NOW = 1_800_000_000_000L;

    private PhilterSignInService signInService;
    private AuditLogService auditLogService;
    private PhilterSessionValidationFilter filter;
    private MockHttpSession session;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        signInService = mock(PhilterSignInService.class);
        auditLogService = mock(AuditLogService.class);
        filter = new PhilterSessionValidationFilter(signInService, auditLogService,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        session = new MockHttpSession();
        request = new MockHttpServletRequest("GET", "/queue");
        request.setSession(session);
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static CurrentUser active(final boolean active) {
        final CurrentUser me = new CurrentUser();
        me.setUsername("jdoe");
        me.setActive(active);
        return me;
    }

    @Test
    void acceptedKeyIsCheckedAndTheRequestContinues() throws Exception {
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(NOW, session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT));
    }

    @Test
    void keyIsNotCheckedAgainWithinTheInterval() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 30_000);

        filter.doFilter(request, response, chain);

        verify(signInService, never()).currentUser(any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectedKeyEndsTheSessionAndReturnsToSignIn() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 61_000);
        when(signInService.currentUser("enc-key")).thenThrow(new UnauthorizedException("expired"));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("/login?expired", response.getRedirectedUrl());
        verify(auditLogService).logForUser(eq("jdoe"), eq("LOGOUT"), eq("User"), eq(null),
                eq(AuditLogService.OUTCOME_SUCCESS), any());
    }

    @Test
    void rejectedKeyOnTheApiIsA401() throws Exception {
        request.setRequestURI("/api/v1/queue");
        when(signInService.currentUser("enc-key")).thenThrow(new UnauthorizedException("revoked"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void deactivatedUserEndsTheSession() throws Exception {
        when(signInService.currentUser("enc-key")).thenReturn(active(false));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unreachablePhilterDoesNotEndTheSessionAndIsCheckedAgainNextTime() throws Exception {
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("connection refused"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT));
    }

    @Test
    void otherAuthenticationsAreLeftAlone() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("a@example.com", null, List.of()));

        filter.doFilter(request, response, chain);

        verify(signInService, never()).currentUser(any());
        verify(chain).doFilter(request, response);
    }
}
