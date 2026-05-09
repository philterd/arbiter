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
 * Rejects session-cookie authentication for the small set of {@code /api/**} endpoints
 * intended for programmatic (Bearer-only) clients.
 *
 * <p>{@code /api/**} hosts two kinds of endpoint:
 * <ol>
 *   <li><strong>Browser-UI endpoints</strong> (queue, batches, span CRUD, document
 *       comments / explain / certificate / history, policies editor, Ollama models,
 *       review locks). These are AJAX calls from logged-in users' browsers and rely
 *       on the same session cookie the rest of the UI uses. Cross-origin attacks on
 *       these are blocked by the browser's same-origin policy plus the absence of a
 *       permissive CORS configuration — a malicious page can't trigger a JSON
 *       request because Arbiter doesn't allow any cross-origin requests.</li>
 *   <li><strong>External programmatic endpoints</strong> (the documented ingest,
 *       search, finalize, and per-document audit-export endpoints). These are
 *       intended to be called by scripts and integrations holding a personal API
 *       key, and must <em>not</em> accept the session cookie of a logged-in
 *       admin who happens to visit a malicious page — a CSRF on {@code /ingest}
 *       could push fake documents into a batch under that admin's identity.</li>
 * </ol>
 *
 * <p>This filter enforces Bearer-only authentication on category 2 only. The
 * companion {@link ApiKeyAuthFilter} marks Bearer-authenticated requests via the
 * {@link ApiKeyAuthFilter#BEARER_AUTH_ATTR} request attribute. For each request
 * matching one of the programmatic-only paths, this filter checks that attribute;
 * if the request is authenticated but was <em>not</em> Bearer-authenticated, it
 * clears the SecurityContext so the downstream {@code AuthorizationFilter}
 * treats the call as anonymous and returns 401. Category 1 (browser UI) and
 * non-{@code /api/**} requests are passed through untouched.
 */
public class ApiSessionRejectingFilter extends OncePerRequestFilter {

    /**
     * Path prefixes that must be Bearer-only. Anything matching these (anchored on
     * a slash boundary so {@code /api/v1/searchable} doesn't accidentally match)
     * rejects session auth.
     */
    private static final String[] BEARER_ONLY_PREFIXES = {
            "/api/v1/ingest",
            "/api/v1/search",
    };

    /**
     * Method+path pairs that must be Bearer-only. Used for endpoints that share a
     * URL prefix with browser-UI endpoints (e.g. {@code /api/v1/documents/{id}/finalize}
     * lives under {@code /api/v1/documents/} which the browser UI also uses for span
     * reads). Matched as {@code (method, suffix)} on the request URI.
     */
    private static final String[] BEARER_ONLY_DOCUMENT_SUFFIXES = {
            "/finalize",      // POST /api/v1/documents/{id}/finalize  (Export)
            "/audit",         // GET  /api/v1/documents/{id}/audit     (Export)
    };

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (isBearerOnly(request)) {
            final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
                // Authenticated, but not via the Bearer filter. The only other source is the
                // session cookie via SecurityContextHolderFilter — drop it for these paths so
                // the request is treated as anonymous downstream and gets a 401.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isBearerOnly(final HttpServletRequest request) {
        final String path = pathWithoutContext(request);
        if (path == null) return false;
        for (String prefix : BEARER_ONLY_PREFIXES) {
            if (path.equals(prefix)
                    || path.startsWith(prefix + "/")
                    || path.startsWith(prefix + "?")) {
                return true;
            }
        }
        if (path.startsWith("/api/v1/documents/")) {
            for (String suffix : BEARER_ONLY_DOCUMENT_SUFFIXES) {
                if (path.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private static String pathWithoutContext(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        if (uri == null) return null;
        final String contextPath = request.getContextPath();
        return (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
    }
}
