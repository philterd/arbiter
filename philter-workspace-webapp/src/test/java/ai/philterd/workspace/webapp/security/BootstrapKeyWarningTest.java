/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapKeyWarningTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
    private PhilterSignInService signIn;
    private BootstrapKeyWarning warning;

    @BeforeEach
    void setUp() {
        signIn = mock(PhilterSignInService.class);
        final Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(final java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        warning = new BootstrapKeyWarning(signIn, clock);
    }

    private static PhilterSessionAuthentication signedIn(final String role) {
        return new PhilterSessionAuthentication("someone", "enc-" + role,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void onlyAdministratorsSignedInThroughPhilterAreWarned() throws Exception {
        when(signIn.bootstrapKeyActive(any())).thenReturn(true);

        assertFalse(warning.show(null));
        assertFalse(warning.show(signedIn("USER")));
        assertFalse(warning.show(signedIn("AUDITOR")));
        assertFalse(warning.show(new UsernamePasswordAuthenticationToken("api", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
        verify(signIn, never()).bootstrapKeyActive(any());

        assertTrue(warning.show(signedIn("ADMIN")));
        verify(signIn).bootstrapKeyActive("enc-ADMIN");
    }

    @Test
    void theAnswerIsCachedForAMinute() throws Exception {
        when(signIn.bootstrapKeyActive(any())).thenReturn(true, false);

        assertTrue(warning.show(signedIn("ADMIN")));
        now.set(now.get().plus(Duration.ofSeconds(59)));
        assertTrue(warning.show(signedIn("ADMIN")));
        verify(signIn, times(1)).bootstrapKeyActive(any());

        now.set(now.get().plus(Duration.ofSeconds(2)));
        assertFalse(warning.show(signedIn("ADMIN")), "the key was revoked");
    }

    @Test
    void anUnreachablePhilterShowsNoWarningAndIsAskedAgainAMinuteLater() throws Exception {
        when(signIn.bootstrapKeyActive(any())).thenThrow(new IOException("connection refused")).thenReturn(true);

        assertFalse(warning.show(signedIn("ADMIN")));
        assertFalse(warning.show(signedIn("ADMIN")), "not asked again on the next page");
        verify(signIn, times(1)).bootstrapKeyActive(any());

        now.set(now.get().plus(Duration.ofSeconds(61)));
        assertTrue(warning.show(signedIn("ADMIN")));
    }
}
