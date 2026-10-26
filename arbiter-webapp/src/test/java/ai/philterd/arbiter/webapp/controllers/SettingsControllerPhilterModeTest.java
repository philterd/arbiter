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
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.arbiter.webapp.security.PhilterSignInService;
import ai.philterd.arbiter.webapp.security.TotpService;
import ai.philterd.philter.model.exceptions.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Settings for a person signed in through Philter: Philter holds the password and the MFA. */
class SettingsControllerPhilterModeTest {

    private static final String TEST_KEY_B64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private PhilterSignInService signInService;
    private SettingsController controller;
    private final PhilterSessionAuthentication philter = new PhilterSessionAuthentication("jdoe", "enc-key",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        signInService = mock(PhilterSignInService.class);
        final ObjectProvider<PhilterSignInService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(signInService);
        controller = new SettingsController(userRepository, passwordEncoder, mock(AuditLogService.class),
                mock(UserSettingsService.class), mock(TotpService.class), mock(ApiKeyHashingService.class),
                new SymmetricCipher(TEST_KEY_B64), mock(SessionRegistry.class), services);
        final User shadow = new User();
        shadow.setId("u1");
        shadow.setEmail("jdoe");
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(shadow));
    }

    private static String error(final RedirectAttributesModelMap flash) {
        final Object e = flash.getFlashAttributes().get("error");
        return e == null ? null : e.toString();
    }

    @Test
    void localMfaIsNotOffered() {
        final RedirectAttributesModelMap setup = new RedirectAttributesModelMap();
        assertEquals("redirect:/settings", controller.mfaSetup(philter, new MockHttpSession(),
                new ExtendedModelMap(), setup));
        assertEquals(SettingsController.MFA_IN_PHILTER, error(setup));

        final RedirectAttributesModelMap enable = new RedirectAttributesModelMap();
        controller.mfaEnable("pw", "123456", false, philter, new MockHttpSession(), enable);
        assertEquals(SettingsController.MFA_IN_PHILTER, error(enable));

        final RedirectAttributesModelMap disable = new RedirectAttributesModelMap();
        controller.mfaDisable("pw", "123456", philter, disable);
        assertEquals(SettingsController.MFA_IN_PHILTER, error(disable));
    }

    @Test
    void apiKeysAreNotAvailable() {
        final RedirectAttributesModelMap generate = new RedirectAttributesModelMap();
        controller.generateApiKey("right-password-1234", philter, generate);
        assertEquals(SettingsController.API_KEYS_OFF, error(generate));

        final RedirectAttributesModelMap revoke = new RedirectAttributesModelMap();
        controller.revokeApiKey("right-password-1234", philter, revoke);
        assertEquals(SettingsController.API_KEYS_OFF, error(revoke));
        verify(userRepository, never()).save(any());
    }

    @Test
    void passwordChangeGoesToPhilterAndEndsTheSession() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        assertEquals("redirect:/login?passwordChanged", controller.changePassword("old-password-12345",
                "new-password-1234567", "new-password-1234567", philter, request, new RedirectAttributesModelMap()));

        verify(signInService).changePassword("enc-key", "old-password-12345", "new-password-1234567");
        assertTrue(session.isInvalid());
    }

    @Test
    void passwordRefusedByPhilterStaysOnSettings() throws Exception {
        doThrow(new ClientException("Bad Request", 400, "too short"))
                .when(signInService).changePassword(any(), any(), any());
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        assertEquals("redirect:/settings", controller.changePassword("old-password-12345", "short", "short",
                philter, new MockHttpServletRequest(), flash));
        assertTrue(error(flash).contains("16 characters"));
    }
}
