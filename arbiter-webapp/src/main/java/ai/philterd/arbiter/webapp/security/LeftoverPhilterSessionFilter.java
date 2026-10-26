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

import ai.philterd.arbiter.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * With local sign-in, ends a session that was signed in through Philter before Arbiter was switched back
 * to local sign-in. Nothing checks such a session against Philter in local mode, so it would otherwise
 * keep its role however Philter has changed the person since.
 */
public class LeftoverPhilterSessionFilter extends OncePerRequestFilter {

    private final AuditLogService auditLogService;

    public LeftoverPhilterSessionFilter(final AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final HttpSession session = request.getSession(false);
        if (!(auth instanceof PhilterSessionAuthentication) || session == null) {
            chain.doFilter(request, response);
            return;
        }
        auditLogService.logForUser(auth.getName(), "LOGOUT", "User", null,
                AuditLogService.OUTCOME_SUCCESS, Map.of("reason", "sign_in_mode_changed"));
        session.invalidate();
        SecurityContextHolder.clearContext();
        final String path = request.getRequestURI();
        if (path != null && path.startsWith(request.getContextPath() + "/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }
}
