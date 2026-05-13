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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Encrypt-at-rest contract for {@link SettingsController}'s TOTP write/read paths. A DB
 * compromise — leaked backup, misconfigured Mongo, admin-readable export — used to hand
 * the attacker enough to compute the victim's current TOTP indefinitely. After the fix,
 * the persisted value is {@code SymmetricCipher.FIELD_PREFIX} followed by AES-GCM
 * ciphertext; the raw shared secret never lands in {@code users.totpSecret}.
 *
 * <p>The cipher is a real {@link SymmetricCipher} built from a fixed 32-byte base64 key
 * (not mocked) so the test verifies the actual encrypt-then-decrypt round trip and the
 * presence of the on-disk marker.
 */
class SettingsControllerTotpEncryptionTest {

    /** A 32-byte key — all zeros — base64-encoded. Test-only. */
    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    /** Realistic-looking RFC 4648 base32 TOTP shared secret (as the totp library emits). */
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
    private static Authentication userAuth(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null);
    }
    private static User existingUser(final String email) {
        final User u = new User();
        u.setId("u-1");
        u.setEmail(email);
        u.setPasswordHash("hash");
        return u;
    }

    // ---------- mfaEnable: writes ciphertext, never plaintext ----------

    @Test
    void mfaEnablePersistsEncryptedTotpSecretWithFieldPrefix() {
        // The pending setup secret is held in the HTTP session, then transferred to the
        // user row when /settings/mfa/enable POSTs with a valid code. The persisted value
        // must NOT equal the raw secret.
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(userRepository.findByEmail("alice@x.com"))
                .thenReturn(Optional.of(existingUser("alice@x.com")));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        controller.mfaEnable("pw", "123456", false, userAuth("alice@x.com"), session, flash());

        final ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        final String persisted = saved.getValue().getTotpSecret();
        assertNotEquals(RAW_SECRET, persisted,
                "raw TOTP secret leaked into users.totpSecret");
        assertTrue(persisted != null && persisted.startsWith(SymmetricCipher.FIELD_PREFIX),
                "persisted value missing the at-rest field marker: " + persisted);
        // And the round-trip must recover the original secret.
        assertEquals(RAW_SECRET, cipher.decryptField(persisted));
        // MFA is now enabled.
        assertTrue(saved.getValue().isMfaEnabled());
        verify(auditLogService).log(eq("MFA_ENABLED"), eq("User"), eq("u-1"), any());
    }

    @Test
    void mfaEnableDoesNotPersistAnythingResemblingRawSecret() {
        // Stricter than the prefix check: the ciphertext must not contain the raw secret
        // as a substring (defense against a future bug that, say, base64-encodes "iv ||
        // plaintext" instead of "iv || ciphertext").
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(userRepository.findByEmail("alice@x.com"))
                .thenReturn(Optional.of(existingUser("alice@x.com")));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        controller.mfaEnable("pw", "123456", false, userAuth("alice@x.com"), session, flash());

        final ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        final String persisted = saved.getValue().getTotpSecret();
        assertFalse(persisted.contains(RAW_SECRET),
                "ciphertext contains the raw secret as a substring: " + persisted);
        // Base64 of the raw secret bytes (the obvious other leak shape) must not appear
        // either. Use no padding so we don't depend on the encoder variant.
        final String b64 = Base64.getEncoder().withoutPadding().encodeToString(
                RAW_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(persisted.contains(b64),
                "ciphertext contains base64 of raw secret: " + persisted);
    }

    @Test
    void mfaEnableRejectsInvalidCodeWithoutPersistingAnySecret() {
        final HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("TOTP_SETUP_SECRET")).thenReturn(RAW_SECRET);
        when(userRepository.findByEmail("alice@x.com"))
                .thenReturn(Optional.of(existingUser("alice@x.com")));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("999999"))).thenReturn(false);

        controller.mfaEnable("pw", "999999", false, userAuth("alice@x.com"), session, flash());

        // The user row is not touched on a bad code — nothing to encrypt, nothing to save.
        verify(userRepository, never()).save(any(User.class));
        verify(auditLogService, never()).log(eq("MFA_ENABLED"), any(), any(), any());
    }

    // ---------- mfaDisable: reads ciphertext, calls totpService with plaintext ----------

    @Test
    void mfaDisableDecryptsStoredSecretBeforeVerifyingCode() {
        // The user row carries an enc:v1: blob (the post-fix shape). The controller must
        // pass the *decrypted* secret — not the blob — to TotpService.verify.
        final User u = existingUser("alice@x.com");
        u.setMfaEnabled(true);
        u.setTotpSecret(cipher.encryptField(RAW_SECRET));
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        controller.mfaDisable("pw", "123456", userAuth("alice@x.com"), flash());

        verify(totpService).verify(eq(RAW_SECRET), eq("123456"));
        final ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertNull(saved.getValue().getTotpSecret(), "totpSecret should be cleared on disable");
        assertFalse(saved.getValue().isMfaEnabled());
    }

    @Test
    void mfaDisableStillWorksForLegacyPlaintextRows() {
        // Backwards-compat: accounts whose MFA was set up before the encryption roll-out
        // have totpSecret stored verbatim (no FIELD_PREFIX). decryptField is documented
        // as pass-through for those values — confirm the controller honours that path so
        // existing users can still disable MFA without admin intervention.
        final User u = existingUser("alice@x.com");
        u.setMfaEnabled(true);
        u.setTotpSecret(RAW_SECRET); // legacy plaintext row
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(eq(RAW_SECRET), eq("123456"))).thenReturn(true);

        controller.mfaDisable("pw", "123456", userAuth("alice@x.com"), flash());

        verify(totpService).verify(eq(RAW_SECRET), eq("123456"));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void mfaDisableRejectsInvalidCodeAndPreservesEncryptedSecret() {
        final User u = existingUser("alice@x.com");
        u.setMfaEnabled(true);
        final String encrypted = cipher.encryptField(RAW_SECRET);
        u.setTotpSecret(encrypted);
        when(userRepository.findByEmail("alice@x.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(eq("pw"), eq("hash"))).thenReturn(true);
        when(totpService.verify(anyString(), eq("000000"))).thenReturn(false);

        controller.mfaDisable("pw", "000000", userAuth("alice@x.com"), flash());

        // No save on a bad code; the encrypted secret remains on the user row.
        verify(userRepository, never()).save(any(User.class));
        assertEquals(encrypted, u.getTotpSecret(),
                "secret must remain encrypted-at-rest on a failed disable attempt");
        assertTrue(u.isMfaEnabled(), "MFA must remain enabled on a failed disable attempt");
    }

    @Test
    void encryptFieldIsIdempotentEvenIfSaveCycleRepeats() {
        // Defensive: if a future code path re-saves a User without re-loading, the
        // already-encrypted totpSecret must not get re-encrypted into a double-wrapped
        // blob that no longer decrypts to RAW_SECRET.
        final String once = cipher.encryptField(RAW_SECRET);
        final String twice = cipher.encryptField(once);
        assertEquals(once, twice, "encryptField re-encrypted an already-encrypted value");
        assertEquals(RAW_SECRET, cipher.decryptField(twice));
    }
}
