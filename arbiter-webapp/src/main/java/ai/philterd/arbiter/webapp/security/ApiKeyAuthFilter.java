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
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.ApiKeyHashingService;
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
import java.util.stream.Collectors;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_PREFIX = "/api/";

    /**
     * Request attribute set on requests whose SecurityContext was populated by this filter
     * (i.e. the caller presented a valid Bearer API key). The companion
     * {@link ApiSessionRejectingFilter} reads this attribute to decide whether to clear the
     * context on {@code /api/**} requests that authenticated via session cookie instead.
     */
    public static final String BEARER_AUTH_ATTR = "ai.philterd.arbiter.bearerAuth";

    private final UserRepository userRepository;
    private final ApiKeyHashingService apiKeyHashingService;

    public ApiKeyAuthFilter(final UserRepository userRepository,
                            final ApiKeyHashingService apiKeyHashingService) {
        this.userRepository = userRepository;
        this.apiKeyHashingService = apiKeyHashingService;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {

        // On /api/** paths, always attempt Bearer extraction even when a session cookie has
        // already populated the SecurityContext. If both are present, Bearer wins: we replace
        // the session-derived auth and mark the request so ApiSessionRejectingFilter keeps it.
        // On non-API paths (the UI), skip extraction when already authenticated so that the
        // session is not replaced by a stale or invalid token.
        final boolean isApiPath = isApiPath(request);
        final boolean alreadyAuthenticated = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated();

        if (isApiPath || !alreadyAuthenticated) {
            final String apiKey = extractBearerToken(request);
            if (apiKey != null && !apiKey.isBlank()) {
                final String apiKeyHash = apiKeyHashingService.hash(apiKey);
                userRepository.findByApiKey(apiKeyHash).ifPresent(user -> {
                    final Set<SimpleGrantedAuthority> authorities = (user.getRoles() == null ? Set.<String>of() : user.getRoles())
                            .stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .collect(Collectors.toSet());
                    final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user.getEmail(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    request.setAttribute(BEARER_AUTH_ATTR, Boolean.TRUE);
                });
            }
        }

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
