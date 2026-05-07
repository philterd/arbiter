/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.InboxService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import ai.philterd.arbiter.service.UserNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuditLogService auditLogService;
    private NotificationSettingsService notificationSettingsService;
    private UserNotificationService userNotificationService;
    private InboxService inboxService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        auditLogService = mock(AuditLogService.class);
        notificationSettingsService = mock(NotificationSettingsService.class);
        userNotificationService = mock(UserNotificationService.class);
        inboxService = mock(InboxService.class);
        controller = new AdminController(userRepository, passwordEncoder, auditLogService,
                notificationSettingsService, userNotificationService, inboxService);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    @Test
    void rejectsBlankEmail() {
        final RedirectAttributes ra = flash();
        controller.create(" ", "password", false, false, ra);
        assertEquals("Email address is required.", error(ra));
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsInvalidEmail() {
        final RedirectAttributes ra = flash();
        controller.create("not-an-email", "password", false, false, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid email address"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsShortPassword() {
        final RedirectAttributes ra = flash();
        controller.create("a@b.com", "abc", false, false, ra);
        assertEquals("Password must be at least 4 characters.", error(ra));
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(new User()));

        final RedirectAttributes ra = flash();
        controller.create("A@b.com", "password", false, false, ra);
        assertEquals("Email \"a@b.com\" is already taken.", error(ra));
        verify(userRepository, never()).save(any());
    }

    @Test
    void sendEmailWhenDisabledStillCreatesUserButFlagsError() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        final NotificationSettings settings = new NotificationSettings();
        settings.setEnabled(false);
        when(notificationSettingsService.load()).thenReturn(settings);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", "password", false, true, ra);

        verify(userRepository).save(any(User.class));
        assertEquals(
                "User created, but outbound email is not enabled, so no welcome email was sent.",
                error(ra));
        verify(userNotificationService, never()).sendNewUserCredentials(anyString(), anyString());
    }

    @Test
    void sendEmailWhenEnabledCallsNotificationService() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        final NotificationSettings settings = new NotificationSettings();
        settings.setEnabled(true);
        when(notificationSettingsService.load()).thenReturn(settings);
        when(userNotificationService.sendNewUserCredentials(anyString(), anyString())).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", "password", true, true, ra);

        verify(userRepository).save(any(User.class));
        verify(userNotificationService).sendNewUserCredentials("a@b.com", "password");
        assertNotNull(success(ra));
        assertTrue(success(ra).contains("Welcome email sent"));
    }

    @Test
    void sendEmailFailureSurfacedAsError() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        final NotificationSettings settings = new NotificationSettings();
        settings.setEnabled(true);
        when(notificationSettingsService.load()).thenReturn(settings);
        when(userNotificationService.sendNewUserCredentials(anyString(), anyString())).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", "password", false, true, ra);

        verify(userRepository).save(any(User.class));
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("could not be sent"));
    }
}
