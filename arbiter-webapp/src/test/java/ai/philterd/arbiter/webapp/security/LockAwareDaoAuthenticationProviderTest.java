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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for the auth-time lockout: drives the provider through bad/good
 * credentials and asserts that a 5-failure run produces a {@link LockedException} on the
 * 6th call without ever reaching the password matcher. Also verifies that a {@code
 * onSuccess(...)} on a verified user clears the counter.
 */
class LockAwareDaoAuthenticationProviderTest {

    private UserDetailsService userDetailsService;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttempts;
    private LockAwareDaoAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(UserDetailsService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttempts = new LoginAttemptService();

        final UserDetails alice = org.springframework.security.core.userdetails.User
                .withUsername("alice@x.com")
                .password("hash")
                .authorities("ROLE_USER")
                .build();
        when(userDetailsService.loadUserByUsername("alice@x.com")).thenReturn(alice);

        // Configure the encoder permissively — individual tests will tighten as needed.
        lenient().when(passwordEncoder.matches(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        provider = new LockAwareDaoAuthenticationProvider(userDetailsService, passwordEncoder, loginAttempts);
    }

    private Authentication tokenFor(final String email, final String password, final String ip) {
        final UsernamePasswordAuthenticationToken t =
                new UsernamePasswordAuthenticationToken(email, password);
        final HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(ip);
        when(req.getSession(false)).thenReturn(null);
        t.setDetails(new WebAuthenticationDetails(req));
        return t;
    }

    @Test
    void successfulAuthenticationClearsAnyPriorFailures() {
        // 4 failures, then a success on the 5th attempt.
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        when(passwordEncoder.matches("good", "hash")).thenReturn(true);

        for (int i = 0; i < 4; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        }
        provider.authenticate(tokenFor("alice@x.com", "good", "1.1.1.1"));

        // Fresh attempts now: 5 more bad attempts must run before lock kicks in.
        for (int i = 0; i < 4; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        }
        // 5th still fails as BadCreds (lock not yet armed).
        assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        // Now we're locked.
        final LockedException locked = assertThrows(LockedException.class,
                () -> provider.authenticate(tokenFor("alice@x.com", "good", "1.1.1.1")));
        assertTrue(locked.getMessage().toLowerCase().contains("locked"));
    }

    @Test
    void fiveBadCredentialFailuresLocksTheAccount() {
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

        // The first MAX_FAILURES throw BadCredentialsException as the password actually
        // mismatches; the next call short-circuits with LockedException.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        }
        assertThrows(LockedException.class,
                () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        assertTrue(loginAttempts.isLocked("alice@x.com", "1.1.1.1"));
    }

    @Test
    void lockIsScopedByIp() {
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        when(passwordEncoder.matches("good", "hash")).thenReturn(true);

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            assertThrows(BadCredentialsException.class,
                    () -> provider.authenticate(tokenFor("alice@x.com", "bad", "1.1.1.1")));
        }
        // Same user, different IP — must still be allowed to authenticate.
        final Authentication ok = provider.authenticate(tokenFor("alice@x.com", "good", "9.9.9.9"));
        assertEquals("alice@x.com", ok.getName());
    }

    @Test
    void unknownUserCountsAgainstTheLock() {
        // Unknown email path: still an authentication failure, must increment the counter.
        when(userDetailsService.loadUserByUsername("ghost@x.com"))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("no"));

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            // Spring's DaoAuthenticationProvider hides UsernameNotFoundException as
            // BadCredentialsException to defeat enumeration; either way it counts.
            assertThrows(org.springframework.security.core.AuthenticationException.class,
                    () -> provider.authenticate(tokenFor("ghost@x.com", "anything", "1.1.1.1")));
        }
        assertTrue(loginAttempts.isLocked("ghost@x.com", "1.1.1.1"),
                "unknown-user failures should also count toward the lock so the lockout "
                        + "isn't an oracle for which emails exist");
    }

    @Test
    void anonymousAuthoritiesPassThroughOnSuccess() {
        when(passwordEncoder.matches("good", "hash")).thenReturn(true);
        final Authentication out = provider.authenticate(tokenFor("alice@x.com", "good", "1.1.1.1"));
        assertEquals("alice@x.com", out.getName());
        assertEquals(Set.of("ROLE_USER"), out.getAuthorities().stream()
                .map(Object::toString).collect(java.util.stream.Collectors.toSet()));
    }
}
