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

import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Ends the Arbiter session of a person signed in through Philter once Philter rejects their session key:
 * when it passes its idle timeout or maximum lifetime, is revoked, or is revoked by a password reset or
 * deactivation. Checks the key with {@code GET /api/users/me} at most once per {@link #INTERVAL}, which
 * also keeps Philter's idle timeout moving while the person uses Arbiter. If Philter cannot be reached the
 * request goes ahead and the next one checks again: only a rejected key ends the session.
 */
public class PhilterSessionValidationFilter extends OncePerRequestFilter {

    static final String VALIDATED_AT = "PHILTER_SESSION_VALIDATED_AT";
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(PhilterSessionValidationFilter.class);

    private final PhilterSignInService signInService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public PhilterSessionValidationFilter(final PhilterSignInService signInService,
                                          final AuditLogService auditLogService,
                                          final Clock clock) {
        this.signInService = signInService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final HttpSession session = request.getSession(false);
        if (!(auth instanceof PhilterSessionAuthentication philter) || session == null) {
            chain.doFilter(request, response);
            return;
        }
        final long now = clock.millis();
        if (session.getAttribute(VALIDATED_AT) instanceof Long last && now - last < INTERVAL.toMillis()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            final CurrentUser me = signInService.currentUser(philter.getEncryptedSessionKey());
            if (me == null || !me.isActive()) {
                end(request, response, session, philter);
                return;
            }
            session.setAttribute(VALIDATED_AT, now);
        } catch (UnauthorizedException e) {
            end(request, response, session, philter);
            return;
        } catch (Exception e) {
            log.warn("Could not check a Philter session key: {}", PhilterClients.describe(e));
        }
        chain.doFilter(request, response);
    }

    private void end(final HttpServletRequest request, final HttpServletResponse response,
                     final HttpSession session, final PhilterSessionAuthentication philter) throws IOException {
        auditLogService.logForUser(philter.getName(), "LOGOUT", "User", null,
                AuditLogService.OUTCOME_SUCCESS, Map.of("reason", "philter_session_ended"));
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
