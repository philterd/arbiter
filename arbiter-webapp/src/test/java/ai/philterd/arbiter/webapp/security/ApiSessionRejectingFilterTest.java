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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiSessionRejectingFilterTest {

    private ApiSessionRejectingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiSessionRejectingFilter();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication sessionAuth(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static MockHttpServletRequest req(final String uri) {
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI(uri);
        return r;
    }

    private static MockHttpServletRequest bearerReq(final String uri) {
        final MockHttpServletRequest r = req(uri);
        r.setAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR, Boolean.TRUE);
        return r;
    }

    // ----- /api/** paths (session auth must be stripped) -----

    @Test
    void stripsSessionAuthOnApiPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/batches"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesBearerAuthOnApiPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(bearerReq("/api/v1/batches"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- /api/v1/review/** paths (session auth must be preserved) -----

    @Test
    void preservesSessionAuthOnPulsePath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/review/doc1/pulse"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnReleasePath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/review/doc1/release"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnSimilarPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/review/doc1/similar"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- non-API paths (always untouched) -----

    @Test
    void preservesSessionAuthOnUiPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/queue"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- filter chain always called -----

    @Test
    void alwaysCallsFilterChain() throws Exception {
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletRequest request = req("/api/v1/batches");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ----- context-path stripping -----

    @Test
    void stripsSessionAuthWhenContextPathPresent() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setContextPath("/arbiter");
        r.setRequestURI("/arbiter/api/v1/batches");

        filter.doFilter(r, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthForReviewPathWithContextPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setContextPath("/arbiter");
        r.setRequestURI("/arbiter/api/v1/review/doc1/pulse");

        filter.doFilter(r, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
