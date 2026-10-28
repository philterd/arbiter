/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.UserSettingsService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSignInService;
import ai.philterd.philter.model.exceptions.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A person's settings: Philter holds the password and the MFA. */
class SettingsControllerPhilterModeTest {

    private UserRepository userRepository;
    private PhilterSignInService signInService;
    private SettingsController controller;
    private final PhilterSessionAuthentication philter = new PhilterSessionAuthentication("jdoe", "enc-key",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        signInService = mock(PhilterSignInService.class);
        controller = new SettingsController(userRepository, mock(AuditLogService.class),
                mock(UserSettingsService.class), signInService);
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
