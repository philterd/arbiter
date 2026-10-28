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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private PhilterApiKeys apiKeys;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeys = mock(PhilterApiKeys.class);
        filter = new ApiKeyAuthFilter(apiKeys);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest api(final String authorization) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/batches");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void aPhilterKeyAuthenticatesAsItsUserWithTheirRole() throws Exception {
        when(apiKeys.resolve("sk_key")).thenReturn(new PhilterApiKeys.Caller("ci", "AUDITOR"));
        final MockHttpServletRequest request = api("Bearer sk_key");
        final MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("ci", auth.getName());
        assertEquals(List.of("ROLE_AUDITOR"), auth.getAuthorities().stream().map(Object::toString).toList());
        assertEquals(Boolean.TRUE, request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR));
        assertNotNull(chain.getRequest(), "the request carries on");
    }

    @Test
    void aBearerKeyReplacesASessionOnTheApi() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone-else", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(apiKeys.resolve("sk_key")).thenReturn(new PhilterApiKeys.Caller("ci", "USER"));

        filter.doFilter(api("bearer  sk_key "), new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("ci", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void refusalsAreAnsweredWithoutCallingTheRestOfTheChain() throws Exception {
        final Object[][] cases = {
                {PhilterApiKeys.Refusal.INVALID, 401},
                {PhilterApiKeys.Refusal.MISSING_SCOPE, 403},
                {PhilterApiKeys.Refusal.UNAVAILABLE, 503}};
        for (final Object[] c : cases) {
            org.mockito.Mockito.doThrow(new PhilterApiKeys.RefusedException((PhilterApiKeys.Refusal) c[0]))
                    .when(apiKeys).resolve("sk_key");
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final MockFilterChain chain = new MockFilterChain();

            filter.doFilter(api("Bearer sk_key"), response, chain);

            assertEquals(c[1], response.getStatus(), String.valueOf(c[0]));
            assertNull(chain.getRequest());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test
    void requestsWithoutABearerKeyOrOutsideTheApiAreNotChecked() throws Exception {
        filter.doFilter(api(null), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(api("Basic dXNlcjpwYXNz"), new MockHttpServletResponse(), new MockFilterChain());
        final MockHttpServletRequest page = new MockHttpServletRequest("GET", "/queue");
        page.addHeader("Authorization", "Bearer sk_key");
        filter.doFilter(page, new MockHttpServletResponse(), new MockFilterChain());

        verify(apiKeys, never()).resolve(anyString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
