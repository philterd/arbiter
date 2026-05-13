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
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.Model;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Defends the MFA self-enrollment flow against the session-hijack → MFA takeover attack.
 * Before the fix, anyone holding the victim's session cookie could call
 * {@code GET /settings/mfa/setup} (which always issued a fresh secret) followed by
 * {@code POST /settings/mfa/enable} (which never required the current password) and
 * silently re-bind MFA to a device the attacker controls — locking the legitimate user
 * out of every future login.
 *
 * <p>The two doors that close the attack:
 * <ol>
 *   <li>{@code mfaSetup} refuses when {@code user.isMfaEnabled()} is already true,
 *       forcing the attacker to go through {@code mfaDisable} first (which already
 *       requires the current password + a valid TOTP code).</li>
 *   <li>{@code mfaEnable} requires {@code currentPassword} on every call, so even a
 *       race that beat the setup-time check is refused before the new secret is
 *       persisted.</li>
 * </ol>
 *
 * <p>The cipher is a real {@link SymmetricCipher} so the encrypted-at-rest behavior
 * established by the prior fix is also exercised on the happy path.
 */
class SettingsControllerMfaEnrollSecurityTest {

    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]);
    private static final String RAW_SECRET = "JBSWY3DPEHPK3PXP";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuditLogService auditLogService;
    private UserSettingsService userSettingsService;
    private TotpService totpService;
    private ApiKeyHashingService apiKeyHashingService;
    private SymmetricCipher cipher;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditLogService = mock(AuditLogService.class);
        userSettingsService = mock(UserSettingsService.class);
        totpService = mock(TotpService.class);
        apiKeyHashingService = mock(ApiKeyHashingService.class);
        cipher = new SymmetricCipher(TEST_KEY_B64);
        controller = new SettingsController(userRepository, passwordEncoder, auditLogService,
                userSettingsService, totpService, apiKeyHashingService, cipher);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static Authentication userAuth(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null);
    }
    private static User baseUser(final String email) {
        final User u = new User();
        u.setId("u-1");
        u.setEmail(email);
        u.setPasswordHash("hash");
        return u;
    }

    // ===================================================================
    // GET /settings/mfa/setup
    // ===================================================================

    @Test
    void mfaSetupRefusesWhenMfaAlreadyEnabled() {
        // This is the front-door defense. A session-hijacker on an MFA-enabled account
        // must not be able to mint a fresh setup secret — that's the prerequisite for
        // re-binding the second factor to their own device.
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(true);
        u.setTotpSecret(cipher.encryptField("EXISTING_SECRET"));
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));

        final Model model = new ConcurrentModel();
        final RedirectAttributes ra = flash();
        final String view = controller.mfaSetup(userAuth("alice@x.com"), mock(HttpSession.class), model, ra);

        assertEquals("redirect:/settings", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("MFA is already enabled"),
                "expected re-enroll-blocked error, got: " + error(ra));
        // No QR code or fresh secret should have been put in the model.
        assertEquals(0, model.asMap().size());
        // And no TOTP secret was generated (defence against side-channel on the
        // secret-generator service if a future bug makes generation side-effecting).
        verify(totpService, never()).generateSecret();
    }

    @Test
    void mfaSetupAllowsFirstTimeEnrollment() {
        // The legitimate path: a user without MFA opens /settings/mfa/setup and gets a
        // QR + secret. This must keep working, since the takeover defense only kicks in
        // when MFA is already on.
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        when(totpService.generateSecret()).thenReturn(RAW_SECRET);
        when(totpService.qrCodeDataUri(eq("alice@x.com"), eq(RAW_SECRET)))
                .thenReturn("data:image/png;base64,...");

        final Model model = new ConcurrentModel();
        final HttpSession session = mock(HttpSession.class);
        final String view = controller.mfaSetup(userAuth("alice@x.com"), session, model, flash());

        assertEquals("mfa-setup", view);
        assertEquals(RAW_SECRET, model.asMap().get("secret"));
        verify(session).setAttribute(eq("TOTP_SETUP_SECRET"), eq(RAW_SECRET));
    }

    // ===================================================================
    // POST /settings/mfa/enable
    // ===================================================================

    @Test
    void mfaEnableRefusesWithoutCurrentPassword() {
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        // The valid TOTP code alone used to be enough — this test pins that it isn't.
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        final RedirectAttributes ra = flash();
        final String view = controller.mfaEnable(null, "123456", false,
                userAuth("alice@x.com"), session, ra);

        assertEquals("redirect:/settings/mfa/setup", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("current password is incorrect"),
                "expected password-required error, got: " + error(ra));
        verify(userRepository, never()).save(any(User.class));
        verify(auditLogService, never()).log(eq("MFA_ENABLED"), any(), any(), any());
    }

    @Test
    void mfaEnableRefusesWithWrongCurrentPassword() {
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);
        when(passwordEncoder.matches(eq("wrong-password"), eq("hash"))).thenReturn(false);

        final RedirectAttributes ra = flash();
        final String view = controller.mfaEnable("wrong-password", "123456", false,
                userAuth("alice@x.com"), session, ra);

        assertEquals("redirect:/settings/mfa/setup", view);
        assertTrue(error(ra).toLowerCase().contains("current password is incorrect"));
        verify(userRepository, never()).save(any(User.class));
        verify(auditLogService, never()).log(eq("MFA_ENABLED"), any(), any(), any());
    }

    @Test
    void mfaEnableHappyPathPersistsEncryptedSecret() {
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(eq("right-password"), eq("hash"))).thenReturn(true);
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        final RedirectAttributes ra = flash();
        final String view = controller.mfaEnable("right-password", "123456", false,
                userAuth("alice@x.com"), session, ra);

        assertEquals("redirect:/settings", view);
        final org.mockito.ArgumentCaptor<User> saved =
                org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertTrue(saved.getValue().isMfaEnabled());
        // From the prior fix: persisted secret is encrypted at rest.
        assertTrue(saved.getValue().getTotpSecret().startsWith(SymmetricCipher.FIELD_PREFIX));
        verify(auditLogService).log(eq("MFA_ENABLED"), eq("User"), eq("u-1"), any());
    }

    @Test
    void mfaEnableRefusesWhenMfaAlreadyEnabledEvenWithCorrectPassword() {
        // Defense in depth: the setup-page guard prevents minting a fresh secret, but a
        // racing attacker could try POSTing /enable using a session-stashed secret from
        // before the victim turned MFA on. enable must refuse independently.
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(true);
        // Capture the on-row ciphertext so we can confirm it survives the rejected call.
        // (Calling encryptField a second time produces different bytes — AES-GCM uses a
        // random IV per encryption — so we can't re-compute and compare.)
        final String preExistingCiphertext = cipher.encryptField("EXISTING_SECRET");
        u.setTotpSecret(preExistingCiphertext);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(passwordEncoder.matches(eq("right-password"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        final RedirectAttributes ra = flash();
        final String view = controller.mfaEnable("right-password", "123456", false,
                userAuth("alice@x.com"), session, ra);

        assertEquals("redirect:/settings", view);
        assertTrue(error(ra).contains("MFA is already enabled"),
                "expected already-enabled error, got: " + error(ra));
        verify(userRepository, never()).save(any(User.class));
        // The existing ciphertext on the row must not have been touched.
        assertEquals(preExistingCiphertext, u.getTotpSecret());
        assertEquals("EXISTING_SECRET", cipher.decryptField(u.getTotpSecret()));
    }

    @Test
    void wrongPasswordPreservesSetupSecretSoUserCanRetry() {
        // The TOTP_SETUP_SECRET in session must NOT be cleared on a password mismatch,
        // or the user has to rescan the QR every time they fat-finger their password.
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        controller.mfaEnable("wrong", "123456", false, userAuth("alice@x.com"), session, flash());

        verify(session, never()).removeAttribute(eq("TOTP_SETUP_SECRET"));
    }

    @Test
    void requiredFlagPreservedOnPasswordFailureRedirect() {
        // Forced-enrollment users (admin policy) must land back at the setup page with
        // ?required=true so the cancel-link / banner stay in their forced state.
        final User u = baseUser("alice@x.com");
        u.setMfaEnabled(false);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        final String view = controller.mfaEnable("wrong", "123456", true,
                userAuth("alice@x.com"), session, flash());
        assertEquals("redirect:/settings/mfa/setup?required=true", view);
    }
}
