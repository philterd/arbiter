/*
 * Copyright 2026 Philterd
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
import ai.philterd.arbiter.service.GeneralSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * When the admin has enabled "Require MFA for all users", this interceptor redirects any
 * fully-authenticated user who has not yet enrolled in MFA to the MFA setup page.
 * Requests to the setup/enable endpoints and static resources are always passed through.
 */
public class MfaEnrollmentInterceptor implements HandlerInterceptor {

    private static final String SETUP_PATH = "/settings/mfa/setup";
    private static final String ENABLE_PATH = "/settings/mfa/enable";

    private final GeneralSettingsService generalSettingsService;
    private final UserRepository userRepository;

    public MfaEnrollmentInterceptor(final GeneralSettingsService generalSettingsService,
                                    final UserRepository userRepository) {
        this.generalSettingsService = generalSettingsService;
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

        final String path = request.getRequestURI();
        if (isExempt(path)) {
            return true;
        }

        final ai.philterd.arbiter.model.GeneralSettings gs = generalSettingsService.load();
        if (gs == null || !gs.isRequireMfa()) {
            return true;
        }

        final User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user != null && !user.isMfaEnabled()) {
            response.sendRedirect(request.getContextPath() + SETUP_PATH + "?required=true");
            return false;
        }

        return true;
    }

    private static boolean isAnonymous(final Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ANONYMOUS".equals(a.getAuthority()));
    }

    private static boolean isExempt(final String path) {
        return path.equals(SETUP_PATH)
                || path.equals(ENABLE_PATH)
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
