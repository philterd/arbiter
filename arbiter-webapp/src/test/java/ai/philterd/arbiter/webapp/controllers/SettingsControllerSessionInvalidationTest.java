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
import ai.philterd.arbiter.service.ApiKeyHashingService;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.security.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the R2-F12 contract: changing the password rotates THIS session and
 * expires every other live session for the same principal. A hijacked session
 * cookie must not survive the credential rotation.
 */
class SettingsControllerSessionInvalidationTest {

    /** Reproducible 32-byte key for SymmetricCipher — bytes have no meaning. */
    private static final String TEST_KEY_B64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private SessionRegistry sessionRegistry;
    private SettingsController controller;
    private HttpServletRequest request;
    private HttpSession currentSession;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        sessionRegistry = mock(SessionRegistry.class);
        controller = new SettingsController(
                userRepository, passwordEncoder, mock(AuditLogService.class),
                mock(UserSettingsService.class), mock(TotpService.class),
                mock(ApiKeyHashingService.class), new SymmetricCipher(TEST_KEY_B64),
                sessionRegistry);

        request = mock(HttpServletRequest.class);
        currentSession = mock(HttpSession.class);
        when(currentSession.getId()).thenReturn("current-session-id");
        when(request.getSession(false)).thenReturn(currentSession);

        final User user = new User();
        user.setId("user-1");
        user.setEmail("alice@x.com");
        user.setPasswordHash("old-hash");
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("old-pw"), eq("old-hash"))).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken("alice@x.com", null);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }

    private static SessionInformation sessionInfo(final String id, final String principal) {
        return new SessionInformation(principal, id, new Date());
    }

    @Test
    void successfulPasswordChangeRotatesCurrentSessionId() {
        // The session id under which this request arrived must be rotated so
        // any pre-rotation copy of the cookie is dead even on this browser.
        when(sessionRegistry.getAllSessions("alice@x.com", false)).thenReturn(List.of());

        controller.changePassword("old-pw", "newPasswordOk123", "newPasswordOk123",
                auth(), request, flash());

        verify(request).changeSessionId();
    }

    @Test
    void successfulPasswordChangeExpiresEveryOtherLiveSessionForThePrincipal() {
        // Two other sessions exist for this user (different browsers or a
        // stolen cookie in a third location). Both must be expired; the
        // current session must NOT be.
        final SessionInformation other1 = sessionInfo("other-1", "alice@x.com");
        final SessionInformation other2 = sessionInfo("other-2", "alice@x.com");
        final SessionInformation self = sessionInfo("current-session-id", "alice@x.com");
        when(sessionRegistry.getAllSessions("alice@x.com", false))
                .thenReturn(List.of(self, other1, other2));

        controller.changePassword("old-pw", "newPasswordOk123", "newPasswordOk123",
                auth(), request, flash());

        assertTrue(other1.isExpired(),
                "other live session must be expired");
        assertTrue(other2.isExpired(),
                "other live session must be expired");
        assertFalse(self.isExpired(),
                "the caller's own session must NOT be expired — they keep their seat post-rotation");
    }

    @Test
    void wrongCurrentPasswordDoesNotInvalidateAnything() {
        // If the current-password check fails, no rotation and no session
        // expiration should happen — a probing attacker who guesses passwords
        // shouldn't be able to log everyone out by trying bad passwords
        // against the change form.
        when(passwordEncoder.matches(eq("wrong-pw"), eq("old-hash"))).thenReturn(false);

        controller.changePassword("wrong-pw", "newPasswordOk123", "newPasswordOk123",
                auth(), request, flash());

        verify(request, never()).changeSessionId();
        verify(sessionRegistry, never()).getAllSessions(anyString(), anyBoolean());
    }

    @Test
    void mismatchedNewPasswordsDoNotInvalidate() {
        controller.changePassword("old-pw", "newPasswordOk123", "DIFFERENT_123456",
                auth(), request, flash());

        verify(request, never()).changeSessionId();
        verify(sessionRegistry, never()).getAllSessions(anyString(), anyBoolean());
    }

    @Test
    void tooShortNewPasswordDoesNotInvalidate() {
        controller.changePassword("old-pw", "tooshort", "tooshort",
                auth(), request, flash());

        verify(request, never()).changeSessionId();
        verify(sessionRegistry, never()).getAllSessions(anyString(), anyBoolean());
    }

    @Test
    void optimisticLockingFailureSurfacesAsFlashError() {
        // R2-F13: User has @Version; a concurrent write throws
        // OptimisticLockingFailureException on save. The controller must
        // surface a benign retry message rather than swallowing the conflict
        // (a silent last-write-wins on a credential change is the bug).
        when(sessionRegistry.getAllSessions("alice@x.com", false)).thenReturn(List.of());
        doThrow(new OptimisticLockingFailureException("version mismatch"))
                .when(userRepository).save(any(User.class));

        final RedirectAttributes ra = flash();
        final String view = controller.changePassword(
                "old-pw", "newPasswordOk123", "newPasswordOk123",
                auth(), request, ra);

        assertEquals("redirect:/settings", view);
        final Object err = ra.getFlashAttributes().get("error");
        assertNotNull(err);
        assertTrue(err.toString().toLowerCase().contains("retry")
                        || err.toString().toLowerCase().contains("another session"),
                "expected an actionable conflict message, got: " + err);
        // The session must NOT be rotated on the failure path — there's no new
        // credential in place to defend.
        verify(request, never()).changeSessionId();
    }

    @Test
    void onlyOneSavePerSuccessfulChange() {
        // Sanity: regression guard so a future refactor doesn't accidentally
        // re-save the user (which would, with R2-F13's @Version, throw an
        // OptimisticLockingFailureException on the second write).
        when(sessionRegistry.getAllSessions("alice@x.com", false)).thenReturn(List.of());
        final String view = controller.changePassword("old-pw", "newPasswordOk123",
                "newPasswordOk123", auth(), request, flash());
        assertEquals("redirect:/settings", view);
        verify(userRepository, times(1)).save(any(User.class));
    }
}
