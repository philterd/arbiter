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
import jakarta.servlet.http.HttpServletResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AuditorWriteRejectFilter}. Mocks {@code FilterChain} and exercises
 * the four interesting axes:
 * <ul>
 *   <li>Principal carries only AUDITOR vs admin/user vs no auth at all.</li>
 *   <li>Method is GET (safe) vs POST/PUT/PATCH/DELETE (mutating).</li>
 *   <li>Path is on the self-management allow-list vs anywhere else.</li>
 *   <li>Context path stripping (the filter must work behind a Spring context path).</li>
 * </ul>
 */
class AuditorWriteRejectFilterTest {

    private final AuditorWriteRejectFilter filter = new AuditorWriteRejectFilter();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication authWith(final String... roles) {
        final List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken("alice@x.com", null, authorities);
    }

    @BeforeEach
    void noAuthByDefault() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest req(final String method, final String uri) {
        final MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    // ----- AUDITOR-only principal: writes blocked, reads pass -----

    @Test
    void auditorPostToAdminPathIs403() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/admin/users"), response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getErrorMessage() != null
                        && response.getErrorMessage().toLowerCase().contains("read-only"),
                "expected the 403 body to say read-only: " + response.getErrorMessage());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void auditorGetToAdminPathPassesThrough() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("GET", "/admin/users"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus(),
                "GETs are reads — the filter must not block them.");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void auditorPostToReviewIsBlocked() throws Exception {
        // /review/{id}/approve etc. live on the .anyRequest().authenticated() tier — without
        // this filter, an auditor could POST and approve documents.
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/review/d1/approve"), response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void auditorPostToApiV1IsBlocked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/api/v1/documents/d1/finalize"), response, chain);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    // ----- Self-management allow-list -----

    @Test
    void auditorPostToSettingsPasswordIsAllowed() throws Exception {
        // Auditors must still be able to change their own password and 2FA settings.
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/settings/password"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void auditorPostToLogoutIsAllowed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/logout"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void auditorPostToMfaVerifyIsAllowed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/mfa"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ----- Other principals are unaffected -----

    @Test
    void adminPostIsAllowed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_ADMIN"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/admin/users"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void userPostIsAllowed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_USER"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/review/d1/approve"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void principalWithBothAdminAndAuditorIsTreatedAsAdmin() throws Exception {
        // Defense-in-depth: if a future migration accidentally assigns both roles, the
        // ADMIN bit wins and the filter doesn't fire. AUDITOR-only is the only restricted
        // shape.
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_ADMIN", "ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/admin/users"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedPostFallsThrough() throws Exception {
        // No authentication at all is not the filter's concern — the AuthorizationFilter
        // downstream will produce 401/redirect.
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(req("POST", "/admin/users"), response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ----- Context path handling -----

    @Test
    void auditorPostBehindContextPathStillRespectsSelfManagementAllowList() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith("ROLE_AUDITOR"));
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final MockHttpServletRequest request = req("POST", "/arbiter/settings/password");
        request.setContextPath("/arbiter");

        filter.doFilter(request, response, chain);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus(),
                "self-management allow-list must work when the app is deployed under a context path");
        verify(chain, times(1)).doFilter(any(), any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
