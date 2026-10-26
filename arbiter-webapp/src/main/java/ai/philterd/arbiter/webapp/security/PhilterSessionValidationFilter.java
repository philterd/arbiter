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
import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.repository.UserRepository;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ends the Arbiter session of a person signed in through Philter once Philter rejects their session key:
 * when it passes its idle timeout or maximum lifetime, is revoked, or is revoked by a password reset or
 * deactivation. Also ends it when the person's role has changed, in Philter or in Arbiter, or their
 * Arbiter record was deleted, so they sign in again with the new role, and ends any session left from
 * before Arbiter was switched to Philter sign-in. Checks the key with {@code GET /api/users/me} at most
 * once per {@link #INTERVAL}, which also keeps Philter's idle timeout moving while the person uses
 * Arbiter. If Philter cannot be reached the request goes ahead and the next one checks again: only a
 * rejected key ends the session.
 */
public class PhilterSessionValidationFilter extends OncePerRequestFilter {

    static final String VALIDATED_AT = "PHILTER_SESSION_VALIDATED_AT";
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(PhilterSessionValidationFilter.class);

    private final PhilterSignInService signInService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public PhilterSessionValidationFilter(final PhilterSignInService signInService,
                                          final UserRepository userRepository,
                                          final AuditLogService auditLogService,
                                          final Clock clock) {
        this.signInService = signInService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final HttpSession session = request.getSession(false);
        if (session == null || auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
            chain.doFilter(request, response);
            return;
        }
        // Only installed when people sign in through Philter, where every session is a Philter one.
        // Anything else is a session from before the switch to Philter sign-in.
        if (!(auth instanceof PhilterSessionAuthentication philter)) {
            end(request, response, session, auth.getName(), null, "sign_in_mode_changed");
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
                end(request, response, session, philter.getName(), philter.getEncryptedSessionKey(),
                        "philter_session_ended");
                return;
            }
            // The role is set at sign-in from Philter's role and Arbiter's record. Ending the session
            // when either has changed makes the change apply at the next check, not the next sign-in.
            final User user = userRepository.findByEmail(philter.getName()).orElse(null);
            if (user == null || !hasRole(philter, PhilterUserProvisioner.roleFor(me.getRole(), user.getRoles()))) {
                end(request, response, session, philter.getName(), philter.getEncryptedSessionKey(),
                        "role_changed");
                return;
            }
            session.setAttribute(VALIDATED_AT, now);
        } catch (UnauthorizedException e) {
            end(request, response, session, philter.getName(), null, "philter_session_ended");
            return;
        } catch (Exception e) {
            log.warn("Could not check a Philter session key: {}", PhilterClients.describe(e));
        }
        chain.doFilter(request, response);
    }

    private static boolean hasRole(final PhilterSessionAuthentication philter, final String role) {
        final Set<String> authorities = philter.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return authorities.equals(Set.of("ROLE_" + role));
    }

    /**
     * Ends the Arbiter session, and revokes the Philter key ({@code keyToRevoke}) when Philter has not
     * already rejected it, so it does not stay usable until Philter's timeouts.
     */
    private void end(final HttpServletRequest request, final HttpServletResponse response,
                     final HttpSession session, final String username, final String keyToRevoke,
                     final String reason) throws IOException {
        if (keyToRevoke != null) {
            try {
                signInService.signOut(keyToRevoke);
            } catch (Exception e) {
                log.warn("Could not revoke a Philter session key: {}", PhilterClients.describe(e));
            }
        }
        auditLogService.logForUser(username, "LOGOUT", "User", null,
                AuditLogService.OUTCOME_SUCCESS, Map.of("reason", reason));
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
