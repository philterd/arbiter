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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces the read-only contract on the {@code AUDITOR} role. The
 * {@link SecurityConfig} matchers already gate {@code /admin/**} and the other
 * admin-scoped paths to ADMIN-only for non-GET methods, but the rest of the application
 * (the user-facing review queue, the upload form, the {@code /api/v1/**} endpoints, the
 * lock pulse beacons under {@code /api/v1/review/**}) lives on the
 * {@code .anyRequest().authenticated()} tier where any logged-in principal can mutate.
 *
 * <p>This filter closes that gap with a single rule: a principal whose only role is
 * {@code AUDITOR} is rejected with HTTP 403 on any non-safe HTTP method (POST / PUT /
 * PATCH / DELETE) outside an explicit self-management allow-list. Self-management
 * paths — password change, MFA enroll/disable, API-key generation, logout — stay open
 * so an auditor can still administer their own account.
 *
 * <p>If the principal carries multiple roles (e.g. AUDITOR + ADMIN), the filter does
 * nothing — the other role's permissions decide. AUDITOR is intended to be assigned
 * alone; this filter only fires when AUDITOR is the only authenticated role.
 */
public class AuditorWriteRejectFilter extends OncePerRequestFilter {

    private static final String AUDITOR = "ROLE_AUDITOR";
    private static final String ADMIN = "ROLE_ADMIN";
    private static final String USER = "ROLE_USER";

    /**
     * Paths an auditor is still allowed to mutate. Each entry matches a path prefix on
     * the unstripped request URI. Keep this list short: every entry widens the
     * read-only boundary.
     *
     * <ul>
     *   <li>{@code /login} — POSTed by the form-login handler.</li>
     *   <li>{@code /logout} — Spring Security's logout POST.</li>
     *   <li>{@code /mfa} — TOTP verification on the way to a session.</li>
     *   <li>{@code /settings/**} — password change, 2FA enable/disable, API key
     *       rotation, review preferences.</li>
     *   <li>{@code /invitations/**} — token-redemption POST (auditors typically arrive
     *       through this flow on first sign-in).</li>
     * </ul>
     */
    private static final String[] SELF_MANAGEMENT_PREFIXES = {
            "/login",
            "/logout",
            "/mfa",
            "/settings",
            "/invitations"
    };

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (isAuditorOnly(SecurityContextHolder.getContext().getAuthentication())
                && isMutatingMethod(request.getMethod())
                && !isSelfManagementPath(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Auditor accounts have read-only access; mutating requests are not permitted.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isAuditorOnly(final Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        boolean hasAuditor = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            final String name = a.getAuthority();
            if (ADMIN.equals(name) || USER.equals(name)) return false;
            if (AUDITOR.equals(name)) hasAuditor = true;
        }
        return hasAuditor;
    }

    private static boolean isMutatingMethod(final String method) {
        if (method == null) return false;
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    private static boolean isSelfManagementPath(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        if (uri == null) return false;
        final String contextPath = request.getContextPath();
        final String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        for (String prefix : SELF_MANAGEMENT_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }
}
