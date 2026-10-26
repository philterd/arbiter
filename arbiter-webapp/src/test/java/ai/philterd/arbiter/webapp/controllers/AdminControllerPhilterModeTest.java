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

import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.InvitationRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import ai.philterd.arbiter.service.UserNotificationService;
import ai.philterd.arbiter.webapp.security.InvitationService;
import ai.philterd.arbiter.webapp.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** In philter sign-in mode, accounts, passwords, lockout, and the admin role belong to Philter. */
class AdminControllerPhilterModeTest {

    private UserRepository userRepository;
    private SessionRegistry sessionRegistry;
    private AdminController controller;
    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRegistry = mock(SessionRegistry.class);
        controller = new AdminController(userRepository, mock(PasswordEncoder.class), mock(AuditLogService.class),
                mock(NotificationSettingsService.class), mock(UserNotificationService.class),
                new LoginAttemptService(), mock(InvitationService.class), mock(InvitationRepository.class),
                sessionRegistry, "philter");
    }

    private User user(final String role) {
        final User user = new User();
        user.setId("u1");
        user.setEmail("jdoe");
        user.setRoles(Set.of(role));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        return user;
    }

    private static String error(final RedirectAttributesModelMap flash) {
        final Object e = flash.getFlashAttributes().get("error");
        return e == null ? null : e.toString();
    }

    @Test
    void usersAreNotCreatedHere() {
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.create("new@example.com", false, "initial-password-1", flash);
        assertEquals(AdminController.MANAGED_IN_PHILTER, error(flash));
        verify(userRepository, never()).save(any());
    }

    @Test
    void userCanBeMadeAnAuditor() {
        final User user = user(Roles.USER);
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.edit("u1", "AUDITOR", null, admin, flash);
        assertEquals(null, error(flash));
        assertEquals(Set.of(Roles.AUDITOR), user.getRoles());
    }

    @Test
    void adminRoleAndPasswordsAreLeftToPhilter() {
        user(Roles.USER);
        final RedirectAttributesModelMap toAdmin = new RedirectAttributesModelMap();
        controller.edit("u1", "ADMIN", null, admin, toAdmin);
        assertEquals(AdminController.MANAGED_IN_PHILTER, error(toAdmin));

        final RedirectAttributesModelMap reset = new RedirectAttributesModelMap();
        controller.edit("u1", "USER", "a-new-password-123", admin, reset);
        assertEquals(AdminController.MANAGED_IN_PHILTER, error(reset));

        user(Roles.ADMIN);
        final RedirectAttributesModelMap fromAdmin = new RedirectAttributesModelMap();
        controller.edit("u1", "USER", null, admin, fromAdmin);
        assertEquals(AdminController.MANAGED_IN_PHILTER, error(fromAdmin));
        verify(userRepository, never()).save(any());
    }

    @Test
    void lockoutIsPhilters() {
        user(Roles.USER);
        final RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        controller.unlock("u1", admin, flash);
        assertEquals(AdminController.MANAGED_IN_PHILTER, error(flash));
    }

    @Test
    void changingBetweenUserAndAuditorEndsThePersonsSessions() {
        user(Roles.AUDITOR);
        controller.edit("u1", "USER", null, admin, new RedirectAttributesModelMap());
        verify(sessionRegistry).getAllSessions("jdoe", false);
    }
}
