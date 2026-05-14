/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.webapp.security.LoginAttemptService;
import ai.philterd.arbiter.webapp.security.MfaAuthenticationSuccessHandler;
import ai.philterd.arbiter.webapp.security.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MFA-stage lockout: a user with the correct password but wrong TOTP code can submit at
 * most {@link LoginAttemptService#MAX_FAILURES} guesses before the (email, ip) pair is
 * locked. On lockout the controller drops the pending session and redirects to
 * {@code /login?locked}. A successful TOTP clears the counter.
 */
class MfaControllerLockoutTest {

    private TotpService totpService;
    private UserRepository userRepository;
    private SecurityContextRepository securityContextRepository;
    private LoginAttemptService loginAttemptService;
    private MfaController controller;
    private HttpSession session;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private Authentication pending;

    @BeforeEach
    void setUp() {
        totpService = mock(TotpService.class);
        userRepository = mock(UserRepository.class);
        securityContextRepository = mock(SecurityContextRepository.class);
        loginAttemptService = new LoginAttemptService();
        // The controller decrypts user.getTotpSecret() before verifying. A mock cipher
        // whose decryptField is a pass-through keeps the legacy-plaintext test fixture
        // (`"SECRET"`) working without touching the test's TotpService.verify mocking.
        final ai.philterd.arbiter.service.SymmetricCipher cipher =
                mock(ai.philterd.arbiter.service.SymmetricCipher.class);
        when(cipher.decryptField(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        controller = new MfaController(totpService, userRepository,
                securityContextRepository, loginAttemptService, cipher);

        final User u = new User();
        u.setEmail("alice@x.com");
        u.setMfaEnabled(true);
        u.setTotpSecret("SECRET");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));

        pending = new UsernamePasswordAuthenticationToken("alice@x.com", null);
        session = mock(HttpSession.class);
        when(session.getAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH))
                .thenReturn(pending);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("1.1.1.1");
        response = mock(HttpServletResponse.class);
    }

    @Test
    void wrongCodeRedirectsBackToMfaWithError() {
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("000000")))
                .thenReturn(OptionalLong.empty());

        final String view = controller.verifyMfaCode("000000", session, request, response);

        assertEquals("redirect:/mfa", view);
        verify(session).setAttribute("mfaError", true);
        // Still pending — the user can try again.
        assertFalse(loginAttemptService.isLocked("alice@x.com", "1.1.1.1"));
    }

    @Test
    void fiveWrongCodesLocksAndDropsPendingSession() {
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("000000")))
                .thenReturn(OptionalLong.empty());

        // First MAX-1 wrong codes leave us on /mfa with mfaError=true.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            assertEquals("redirect:/mfa",
                    controller.verifyMfaCode("000000", session, request, response));
        }
        // The MAX-th failure locks the account and bounces back to /login?locked.
        final String view = controller.verifyMfaCode("000000", session, request, response);
        assertEquals("redirect:/login?locked", view);
        verify(session).removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
        assertTrue(loginAttemptService.isLocked("alice@x.com", "1.1.1.1"));
    }

    @Test
    void preLockedSessionIsRedirectedToLoginAndNeverChecksTotp() {
        // Simulate the user reaching /mfa with a pending Authentication, but they'd
        // already burned 5 failures from a prior session — the controller must abort
        // before invoking the TOTP check at all.
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            loginAttemptService.onFailure("alice@x.com", "1.1.1.1");
        }

        final String view = controller.verifyMfaCode("123456", session, request, response);

        assertEquals("redirect:/login?locked", view);
        verify(totpService, never()).verifyAndReturnStep(anyString(),
                anyString());
        verify(session).removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
    }

    @Test
    void correctCodeClearsTheCounter() {
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("000000")))
                .thenReturn(OptionalLong.empty());
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("123456")))
                .thenReturn(OptionalLong.of(123456L));

        // Three wrong attempts, then a correct one — the counter resets.
        for (int i = 0; i < 3; i++) {
            controller.verifyMfaCode("000000", session, request, response);
        }
        final String view = controller.verifyMfaCode("123456", session, request, response);
        assertEquals("redirect:/", view);
        assertFalse(loginAttemptService.isLocked("alice@x.com", "1.1.1.1"));
    }

    @Test
    void replayedCodeWithinWindowIsRefused() {
        // R2-F11: a captured TOTP can otherwise be reused from a parallel browser
        // for 30–60 seconds. With step tracking on User.lastTotpStep, the second
        // submission of the same accepted step gets the same redirect as a wrong
        // code — and counts toward the lockout.
        final User u = userRepository.findByEmail("alice@x.com").orElseThrow();
        u.setLastTotpStep(1234567L);                       // already accepted this step
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("888888")))
                .thenReturn(OptionalLong.of(1234567L));   // same step → replay

        final String view = controller.verifyMfaCode("888888", session, request, response);

        assertEquals("redirect:/mfa", view);
        verify(session).setAttribute("mfaError", true);
        // Replay counts as a failed attempt for lockout — burning the captured code
        // should consume an attempt the attacker can't get back.
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void firstAcceptedCodePersistsItsStep() {
        // After a successful verify the user's lastTotpStep must be set to the
        // accepted step, so a subsequent replay of the same code is refused.
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("123456")))
                .thenReturn(OptionalLong.of(9999L));

        controller.verifyMfaCode("123456", session, request, response);

        final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(Long.valueOf(9999L), captor.getValue().getLastTotpStep());
    }

    @Test
    void newCodeAfterTheStepAdvancesIsAccepted() {
        // The valid TOTP for the NEXT step (still within ±1 of "now") is fine — the
        // step strictly exceeds the previous lastTotpStep, so it isn't a replay.
        final User u = userRepository.findByEmail("alice@x.com").orElseThrow();
        u.setLastTotpStep(1000L);
        when(totpService.verifyAndReturnStep(eq("SECRET"), eq("111111")))
                .thenReturn(OptionalLong.of(1001L));

        final String view = controller.verifyMfaCode("111111", session, request, response);
        assertEquals("redirect:/", view);
    }

    @Test
    void noPendingMfaAuthGoesToLogin() {
        when(session.getAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH))
                .thenReturn(null);

        final String view = controller.verifyMfaCode("123456", session, request, response);

        assertEquals("redirect:/login", view);
        // Must not increment a counter for a missing email.
        assertFalse(loginAttemptService.isEmailLocked(""));
    }
}
