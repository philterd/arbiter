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

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Forces a user whose account is flagged {@code mustChangePassword} onto the
 * Settings page until they rotate the password their admin set for them. The
 * flag is set when an admin creates an account with an initial password (the
 * SMTP-free invite flow) or resets a user's password from the Admin → Users
 * page; it is cleared when the user successfully changes their password from
 * Settings.
 *
 * <p>This is an authorization-time gate, not an authentication gate — sign-in
 * itself succeeds with the password the admin set, but every subsequent
 * request is redirected to {@code /settings} (with a banner explaining why)
 * except for the password-change endpoint, the logout endpoint, and static
 * assets. That keeps the user from approving documents, hitting admin pages,
 * or otherwise exercising the account before they've taken sole knowledge of
 * the credential.
 */
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    private static final String SETTINGS_PATH = "/settings";
    private static final String CHANGE_PASSWORD_PATH = "/settings/password";

    private final UserRepository userRepository;

    public PasswordChangeRequiredInterceptor(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(final HttpServletRequest request,
                             final HttpServletResponse response,
                             final Object handler) throws Exception {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || isAnonymous(auth)) {
            return true;
        }

        // Bearer (API-key) callers present a separate credential — the API key
        // — so the password-rotation gate doesn't apply. The gate exists to
        // prevent an admin who set the initial password from acting via that
        // password; an API key bypasses that admin entirely.
        if (Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
            return true;
        }

        final String path = request.getRequestURI();
        if (isExempt(path)) {
            return true;
        }

        final User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user != null && user.isMustChangePassword()) {
            // API callers (session-cookie /api/v1/**) get a 403 with a short
            // JSON-friendly reason rather than the 302 the browser UI expects —
            // fetch() doesn't follow cross-origin redirects to /settings and
            // the redirect would just produce a confusing CORS error in the
            // browser console.
            if (path != null && path.startsWith("/api/")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Password change required.");
            } else {
                response.sendRedirect(request.getContextPath() + SETTINGS_PATH + "?mustChangePassword=true");
            }
            return false;
        }

        return true;
    }

    private static boolean isAnonymous(final Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ANONYMOUS".equals(a.getAuthority()));
    }

    /**
     * Allow-list of paths the user can still reach while the gate is active:
     * the Settings page itself (so they can see the form), the password-change
     * POST that clears the flag, the MFA endpoints (the MFA gate may also be
     * active and the two have to coexist), logout, and static assets.
     */
    private static boolean isExempt(final String path) {
        return path.equals(SETTINGS_PATH)
                || path.equals(CHANGE_PASSWORD_PATH)
                || path.startsWith("/settings/mfa")
                || path.startsWith("/mfa")
                || path.startsWith("/login")
                || path.startsWith("/logout")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/webjars/")
                || path.startsWith("/error");
    }
}
