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
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.SignInLockedException;
import ai.philterd.philter.model.exceptions.SignInRateLimitedException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterSignInFlowTest {

    private PhilterSignInService signInService;
    private PhilterUserProvisioner provisioner;
    private AuditLogService auditLogService;
    private PhilterSignInFlow flow;

    @BeforeEach
    void setUp() {
        signInService = mock(PhilterSignInService.class);
        provisioner = mock(PhilterUserProvisioner.class);
        auditLogService = mock(AuditLogService.class);
        when(signInService.encrypt(anyString())).thenAnswer(i -> "enc(" + i.getArgument(0) + ")");
        flow = new PhilterSignInFlow(signInService, provisioner, auditLogService);
    }

    private static SignInResponse key(final String apiKey) {
        final SignInResponse response = new SignInResponse();
        response.setApiKey(apiKey);
        response.setUsername("jdoe");
        return response;
    }

    private static CurrentUser me(final String username, final String role) {
        final CurrentUser me = new CurrentUser();
        me.setUsername(username);
        me.setRole(role);
        me.setActive(true);
        return me;
    }

    @Test
    void sessionKeySignsThePersonInWithTheRoleFromPhilter() throws IOException {
        when(signInService.currentUser("enc(sk_session)")).thenReturn(me("jdoe", "admin"));
        when(provisioner.provision("jdoe", "admin")).thenReturn("ADMIN");

        final PhilterSessionAuthentication auth = flow.complete(key("sk_session"), "JDoe");

        assertEquals("jdoe", auth.getName(), "Philter's username, not what was typed");
        assertEquals("enc(sk_session)", auth.getEncryptedSessionKey());
        assertNull(auth.getCredentials());
        assertTrue(auth.isAuthenticated());
        assertEquals("ROLE_ADMIN", auth.getAuthorities().iterator().next().getAuthority());
        assertFalse(auth.toString().contains("sk_session"));
        assertFalse(auth.toString().contains("jdoe"));
        verify(auditLogService).logForUser(eq("jdoe"), eq("LOGIN"), eq("User"), eq(null),
                eq(AuditLogService.OUTCOME_SUCCESS), eq(Map.of("method", "philter")));
    }

    @Test
    void mfaChallengeBecomesAPendingStepWithTheChallengeEncrypted() throws IOException {
        final SignInResponse response = new SignInResponse();
        response.setChallenge("ch_123");

        final PhilterSignInStepException step = assertThrows(PhilterSignInStepException.class,
                () -> flow.complete(response, "jdoe"));

        assertEquals(PendingPhilterSignIn.Step.MFA, step.getPending().step());
        assertEquals("jdoe", step.getPending().username());
        assertEquals("enc(ch_123)", step.getPending().encryptedSecret());
        verify(signInService, never()).currentUser(any());
        verify(auditLogService, never()).logForUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void passwordChangeRequiredIsAStepAndDoesNotSignIn() throws IOException {
        final SignInResponse response = key("sk_limited");
        response.setPasswordChangeRequired(true);

        final PhilterSignInStepException step = assertThrows(PhilterSignInStepException.class,
                () -> flow.complete(response, "jdoe"));

        assertEquals(PendingPhilterSignIn.Step.PASSWORD_CHANGE, step.getPending().step());
        assertEquals("enc(sk_limited)", step.getPending().encryptedSecret());
        verify(signInService, never()).currentUser(any());
    }

    @Test
    void mfaEnrollmentRequiredIsAStep() {
        final SignInResponse response = key("sk_limited");
        response.setMfaEnrollmentRequired(true);

        final PhilterSignInStepException step = assertThrows(PhilterSignInStepException.class,
                () -> flow.complete(response, "jdoe"));

        assertEquals(PendingPhilterSignIn.Step.MFA_ENROLLMENT, step.getPending().step());
    }

    @Test
    void keyIsRevokedWhenThePersonCannotBeLookedUp() throws IOException {
        when(signInService.currentUser("enc(sk_session)")).thenThrow(new IOException("connection reset"));

        final AuthenticationException e = assertThrows(AuthenticationException.class,
                () -> flow.complete(key("sk_session"), "jdoe"));

        assertInstanceOf(AuthenticationServiceException.class, e);
        verify(signInService).signOut("enc(sk_session)");
    }

    @Test
    void wrongPasswordUnknownUserAndDeactivatedUserAreAllBadCredentials() {
        final AuthenticationException e = flow.refused("jdoe", new UnauthorizedException("Unauthorized"));

        assertInstanceOf(BadCredentialsException.class, e);
        verify(auditLogService).logForUser(eq("jdoe"), eq("LOGIN"), eq("User"), eq(null),
                eq(AuditLogService.OUTCOME_FAILURE), eq(Map.of("reason", "BadCredentials", "method", "philter")));
    }

    @Test
    void lockedRateLimitedAndMfaLockedAreAllLocked() {
        assertInstanceOf(LockedException.class, flow.refused("u", new SignInLockedException("locked", 60)));
        assertInstanceOf(LockedException.class, flow.refused("u", new SignInRateLimitedException("slow", 30)));
        assertInstanceOf(LockedException.class, flow.refused("u",
                new ClientException("Forbidden", 403, "MFA is locked.", ErrorReasons.MFA_LOCKED)));
    }

    @Test
    void signInTurnedOffInPhilterOrPhilterUnreachableIsUnavailable() {
        assertInstanceOf(AuthenticationServiceException.class,
                flow.refused("u", new ClientException("Not Found", 404, "Not Found")));
        assertInstanceOf(AuthenticationServiceException.class,
                flow.refused("u", new IOException("connection refused")));
    }

    @Test
    void userAgentIsTruncated() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "x".repeat(2000));
        assertEquals(512, PhilterSignInFlow.userAgent(request).length());
        assertNull(PhilterSignInFlow.userAgent(null));
    }

    @Test
    void keyIsRevokedWhenTheArbiterUserCannotBeSaved() throws IOException {
        when(signInService.currentUser("enc(sk_session)")).thenReturn(me("jdoe", "user"));
        when(provisioner.provision("jdoe", "user"))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("concurrent first sign-in"));

        assertThrows(AuthenticationServiceException.class, () -> flow.complete(key("sk_session"), "jdoe"));
        verify(signInService).signOut("enc(sk_session)");
    }

    @Test
    void keyIsRevokedWhenTheSignInCannotBeAudited() throws IOException {
        when(signInService.currentUser("enc(sk_session)")).thenReturn(me("jdoe", "user"));
        when(provisioner.provision("jdoe", "user")).thenReturn("USER");
        org.mockito.Mockito.doThrow(new IllegalStateException("audit store down")).when(auditLogService)
                .logForUser(eq("jdoe"), eq("LOGIN"), any(), any(), eq(AuditLogService.OUTCOME_SUCCESS), any());

        assertThrows(AuthenticationServiceException.class, () -> flow.complete(key("sk_session"), "jdoe"));
        verify(signInService).signOut("enc(sk_session)");
    }

    @Test
    void anUnsupportedPhilterIsAuditedAsARefusedSignIn() {
        final var refused = flow.unsupported("jdoe", "This Philter is version 5.0.0, which Arbiter does not support.");

        assertInstanceOf(UnsupportedPhilterException.class, refused);
        assertTrue(refused.getMessage().contains("5.0.0"));
        verify(auditLogService).logForUser(eq("jdoe"), eq("LOGIN"), eq("User"), eq(null),
                eq(AuditLogService.OUTCOME_FAILURE), eq(Map.of("reason", "UnsupportedPhilter", "method", "philter")));
    }
}
