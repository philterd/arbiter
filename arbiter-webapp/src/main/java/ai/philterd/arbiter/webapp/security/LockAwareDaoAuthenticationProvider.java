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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps the standard {@link DaoAuthenticationProvider} with the {@link LoginAttemptService}
 * counter so that repeated bad-password attempts lock the (email, ip) pair for 15 minutes,
 * and writes a {@code LOGIN} audit-log entry for every authentication outcome.
 *
 * <p>The provider runs in three phases:
 * <ol>
 *   <li>Pre-check the lock: if {@code (email, ip)} is locked, throw {@link LockedException}
 *       *before* hashing the password — so the lockout doesn't degrade to "you can still try
 *       passwords, we just won't tell you if they worked".</li>
 *   <li>Delegate to {@code super.authenticate(...)} for the actual credential check.</li>
 *   <li>On {@link BadCredentialsException} or {@link UsernameNotFoundException}, increment
 *       the counter and rethrow. On success, clear the counter for that pair.</li>
 * </ol>
 *
 * <p>Both real failures (bad password) and unknown users count toward the same lock so that
 * the lockout doesn't become an oracle for whether the email exists.
 *
 * <p>Auditing is done here rather than via a Spring Security
 * {@code AuthenticationEventPublisher} listener so the audit entry is produced
 * regardless of whether the publisher bean is wired into the local
 * {@code ProviderManager}. Every successful sign-in produces one
 * {@code LOGIN}/{@code SUCCESS} entry; every failed sign-in (bad password, unknown
 * user, or pre-emptive lockout) produces one {@code LOGIN}/{@code FAILURE} entry
 * with a {@code reason} detail naming the exception class.
 */
public class LockAwareDaoAuthenticationProvider extends DaoAuthenticationProvider {

    private final LoginAttemptService loginAttempts;
    private final AuditLogService auditLogService;

    public LockAwareDaoAuthenticationProvider(final UserDetailsService userDetailsService,
                                              final PasswordEncoder passwordEncoder,
                                              final LoginAttemptService loginAttempts,
                                              final AuditLogService auditLogService) {
        setUserDetailsService(userDetailsService);
        setPasswordEncoder(passwordEncoder);
        this.loginAttempts = loginAttempts;
        this.auditLogService = auditLogService;
    }

    @Override
    public Authentication authenticate(final Authentication authentication) throws AuthenticationException {
        final String email = authentication.getName();
        final String ip = ipFrom(authentication);
        if (loginAttempts.isLocked(email, ip)) {
            final LockedException locked = new LockedException("Account is temporarily locked. Try again later.");
            auditFailure(email, locked);
            throw locked;
        }
        try {
            final Authentication out = super.authenticate(authentication);
            loginAttempts.onSuccess(email, ip);
            auditLogService.logForUser(email, "LOGIN", "User", null,
                    AuditLogService.OUTCOME_SUCCESS, null);
            return out;
        } catch (AuthenticationException ex) {
            // Increment the (email, ip) counter only for the credential-related
            // failures the lockout policy targets — service errors (database
            // outages, etc.) shouldn't lock real users out — but every failure
            // still gets an audit entry so an operator reading the audit log
            // sees the full picture of what happened.
            if (ex instanceof BadCredentialsException || ex instanceof UsernameNotFoundException) {
                loginAttempts.onFailure(email, ip);
            }
            auditFailure(email, ex);
            throw ex;
        }
    }

    private void auditFailure(final String email, final AuthenticationException ex) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", ex == null ? "unknown" : ex.getClass().getSimpleName());
        auditLogService.logForUser(email, "LOGIN", "User", null,
                AuditLogService.OUTCOME_FAILURE, details);
    }

    private static String ipFrom(final Authentication auth) {
        final Object details = auth.getDetails();
        if (details instanceof WebAuthenticationDetails wad) {
            return wad.getRemoteAddress() == null ? "" : wad.getRemoteAddress();
        }
        return "";
    }
}
