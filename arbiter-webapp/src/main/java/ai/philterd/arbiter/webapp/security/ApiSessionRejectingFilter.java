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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects session-cookie authentication for {@code /api/**} requests.
 *
 * <p>The API path is intended to be called only with a Bearer API key. Without this filter
 * a logged-in user's browser session cookie would also count as authentication for the API,
 * which combined with the disabled CSRF on {@code /api/**} would allow cross-site requests
 * (a logged-in admin visiting a malicious page could be tricked into POSTing to the API
 * because cookies are sent automatically).
 *
 * <p>The companion {@link ApiKeyAuthFilter} marks requests whose SecurityContext it
 * populated with the {@link ApiKeyAuthFilter#BEARER_AUTH_ATTR} request attribute. This
 * filter runs after both the session-load and Bearer filters; for {@code /api/**} requests
 * that are authenticated but were <em>not</em> marked as Bearer-authenticated, it clears
 * the SecurityContext so the downstream {@code AuthorizationFilter} treats the call as
 * anonymous and returns 401.
 *
 * <p>Non-API paths are passed through untouched — the regular UI continues to use the
 * session cookie.
 *
 * <p>{@code /api/v1/review/**} is exempted from this filter. Those endpoints (pulse,
 * release, similar) are browser-initiated, session-authenticated calls that live under
 * {@code /api/**} only to bypass CSRF for {@code navigator.sendBeacon}. Each operates
 * solely on the requesting user's own lock, so the CSRF risk is negligible.
 */
public class ApiSessionRejectingFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";
    // Exempted: browser-UI endpoints that need session auth and live under /api/** only
    // for navigator.sendBeacon CSRF bypass. Each acts only on the caller's own data.
    private static final String REVIEW_PREFIX = "/api/v1/review/";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (isApiPath(request)) {
            final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
                // Authenticated, but not via the Bearer filter. The only other source is the
                // session cookie via SecurityContextHolderFilter — drop it for /api/** so the
                // request is treated as anonymous downstream.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isApiPath(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        if (uri == null) return false;
        // Strip the context path so /api/** matches whether the app is deployed at root or
        // under a context path.
        final String contextPath = request.getContextPath();
        final String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith(API_PREFIX) && !path.startsWith(REVIEW_PREFIX);
    }
}
