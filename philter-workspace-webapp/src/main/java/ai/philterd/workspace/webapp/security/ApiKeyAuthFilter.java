/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.webapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Authenticates {@code Authorization: Bearer <key>} on {@code /api/**} with a Philter API key: the request
 * runs as the Philter user that owns it ({@link PhilterApiKeys}). A key Philter rejects is answered 401, one
 * without {@code users:read} 403, and 503 when Philter cannot be asked. A Bearer key replaces any session
 * on the same request. Requests without one carry on, and {@link ApiSessionRejectingFilter} keeps the
 * session from counting on {@code /api/**}.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PREFIX = "/api/";

    /**
     * Request attribute set on requests whose SecurityContext was populated by this filter
     * (i.e. the caller presented a valid Bearer API key). The companion
     * {@link ApiSessionRejectingFilter} reads this attribute to decide whether to clear the
     * context on {@code /api/**} requests that authenticated via session cookie instead.
     */
    public static final String BEARER_AUTH_ATTR = "ai.philterd.workspace.bearerAuth";

    private final PhilterApiKeys apiKeys;

    public ApiKeyAuthFilter(final PhilterApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String apiKey = isApiPath(request) ? extractBearerToken(request) : null;
        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        final PhilterApiKeys.Caller caller;
        try {
            caller = apiKeys.resolve(apiKey);
        } catch (PhilterApiKeys.RefusedException e) {
            SecurityContextHolder.clearContext();
            switch (e.refusal()) {
                case MISSING_SCOPE -> response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "The Philter API key must hold the users:read scope.");
                case UNAVAILABLE -> response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Philter could not check the API key.");
                default -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                caller.username(), null, Set.of(new SimpleGrantedAuthority("ROLE_" + caller.role()))));
        request.setAttribute(BEARER_AUTH_ATTR, Boolean.TRUE);
        filterChain.doFilter(request, response);
    }

    private static boolean isApiPath(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        if (uri == null) return false;
        final String contextPath = request.getContextPath();
        final String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith(API_PREFIX);
    }

    private static String extractBearerToken(final HttpServletRequest request) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        if (header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
