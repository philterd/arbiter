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

import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterApiKeysTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
    private PhilterSignInService signIn;
    private PhilterUserProvisioner provisioner;
    private PhilterApiKeys keys;

    @BeforeEach
    void setUp() {
        signIn = mock(PhilterSignInService.class);
        provisioner = mock(PhilterUserProvisioner.class);
        final Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(final java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        keys = new PhilterApiKeys(signIn, provisioner, clock, Duration.ofSeconds(30));
    }

    private static CurrentUser user(final String username, final String role, final boolean active) {
        final CurrentUser me = new CurrentUser();
        me.setUsername(username);
        me.setRole(role);
        me.setActive(active);
        return me;
    }

    @Test
    void resolvesTheKeysUserAndRoleAndCachesTheAnswer() throws Exception {
        when(signIn.currentUserForApiKey("sk_key")).thenReturn(user("ci", "user", true));
        when(provisioner.provision("ci", "user")).thenReturn("USER");

        assertEquals(new PhilterApiKeys.Caller("ci", "USER"), keys.resolve("sk_key"));
        now.set(now.get().plusSeconds(29));
        assertEquals(new PhilterApiKeys.Caller("ci", "USER"), keys.resolve("sk_key"));
        verify(signIn, times(1)).currentUserForApiKey("sk_key");
    }

    @Test
    void aRevokedKeyIsRefusedOnceTheCacheEntryExpires() throws Exception {
        when(signIn.currentUserForApiKey("sk_key")).thenReturn(user("ci", "user", true))
                .thenThrow(new UnauthorizedException("revoked"));
        when(provisioner.provision("ci", "user")).thenReturn("USER");
        keys.resolve("sk_key");

        now.set(now.get().plusSeconds(31));

        assertEquals(PhilterApiKeys.Refusal.INVALID,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_key")).refusal());
    }

    @Test
    void refusalsSayWhyAndAreNotCached() throws Exception {
        final ClientException forbidden = new ClientException("users:read", 403, "missing_scope");
        when(signIn.currentUserForApiKey("sk_no_scope")).thenThrow(forbidden);
        when(signIn.currentUserForApiKey("sk_down")).thenThrow(new IOException("connection refused"));
        when(signIn.currentUserForApiKey("sk_inactive")).thenReturn(user("gone", "user", false));

        assertEquals(PhilterApiKeys.Refusal.MISSING_SCOPE,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_no_scope")).refusal());
        assertEquals(PhilterApiKeys.Refusal.UNAVAILABLE,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_down")).refusal());
        assertEquals(PhilterApiKeys.Refusal.INVALID,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_inactive")).refusal());
        assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_no_scope"));
        verify(signIn, times(2)).currentUserForApiKey("sk_no_scope");
    }

    @Test
    void aServerErrorOrRateLimitIsUnavailableNotInvalid() throws Exception {
        when(signIn.currentUserForApiKey("sk_proxy_error")).thenThrow(new ClientException("Bad Gateway", 502, null));
        when(signIn.currentUserForApiKey("sk_limited")).thenThrow(new ClientException("Too Many Requests", 429, null));
        when(signIn.currentUserForApiKey("sk_not_found")).thenThrow(new ClientException("Not Found", 404, null));

        assertEquals(PhilterApiKeys.Refusal.UNAVAILABLE,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_proxy_error")).refusal());
        assertEquals(PhilterApiKeys.Refusal.UNAVAILABLE,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_limited")).refusal());
        assertEquals(PhilterApiKeys.Refusal.INVALID,
                assertThrows(PhilterApiKeys.RefusedException.class, () -> keys.resolve("sk_not_found")).refusal());
    }

    @Test
    void aNegativeCacheTimeStopsStartup() {
        assertThrows(IllegalStateException.class,
                () -> new PhilterApiKeys(signIn, provisioner, Clock.systemUTC(), Duration.ofSeconds(-1)));
    }
}
