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

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Wraps the standard {@link DaoAuthenticationProvider} with the {@link LoginAttemptService}
 * counter so that repeated bad-password attempts lock the (email, ip) pair for 15 minutes.
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
 */
public class LockAwareDaoAuthenticationProvider extends DaoAuthenticationProvider {

    private final LoginAttemptService loginAttempts;

    public LockAwareDaoAuthenticationProvider(final UserDetailsService userDetailsService,
                                              final PasswordEncoder passwordEncoder,
                                              final LoginAttemptService loginAttempts) {
        setUserDetailsService(userDetailsService);
        setPasswordEncoder(passwordEncoder);
        this.loginAttempts = loginAttempts;
    }

    @Override
    public Authentication authenticate(final Authentication authentication) throws AuthenticationException {
        final String email = authentication.getName();
        final String ip = ipFrom(authentication);
        if (loginAttempts.isLocked(email, ip)) {
            throw new LockedException("Account is temporarily locked. Try again later.");
        }
        try {
            final Authentication out = super.authenticate(authentication);
            loginAttempts.onSuccess(email, ip);
            return out;
        } catch (BadCredentialsException | UsernameNotFoundException ex) {
            loginAttempts.onFailure(email, ip);
            throw ex;
        }
    }

    private static String ipFrom(final Authentication auth) {
        final Object details = auth.getDetails();
        if (details instanceof WebAuthenticationDetails wad) {
            return wad.getRemoteAddress() == null ? "" : wad.getRemoteAddress();
        }
        return "";
    }
}
