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

import ai.philterd.arbiter.model.Invitation;
import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import ai.philterd.arbiter.service.UserNotificationService;
import ai.philterd.arbiter.webapp.security.InvitationService;
import ai.philterd.arbiter.webapp.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private LoginAttemptService loginAttemptService;
    private InvitationService invitationService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        auditLogService = mock(AuditLogService.class);
        notificationSettingsService = mock(NotificationSettingsService.class);
        userNotificationService = mock(UserNotificationService.class);
        loginAttemptService = new LoginAttemptService();
        invitationService = mock(InvitationService.class);
        controller = new AdminController(userRepository, passwordEncoder, auditLogService,
                notificationSettingsService, userNotificationService,
                loginAttemptService, invitationService);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    private static NotificationSettings smtpEnabled() {
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(true);
        return s;
    }

    private static NotificationSettings smtpDisabled() {
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(false);
        return s;
    }

    private InvitationService.IssuedInvitation stubIssue(final String email, final boolean admin) {
        final Invitation inv = new Invitation();
        inv.setId("inv-1");
        inv.setEmail(email);
        inv.setAdmin(admin);
        final InvitationService.IssuedInvitation issued =
                new InvitationService.IssuedInvitation(inv, "tok-12345");
        when(invitationService.issue(eq(email), eq(admin), anySet())).thenReturn(issued);
        return issued;
    }

    // ---------- /admin/users (POST) — invite flow ----------

    @Test
    void rejectsBlankEmail() {
        final RedirectAttributes ra = flash();
        controller.create(" ", false, ra);
        assertEquals("Email address is required.", error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never()).sendInvitation(anyString(), anyString());
    }

    @Test
    void rejectsInvalidEmail() {
        final RedirectAttributes ra = flash();
        controller.create("not-an-email", false, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid email address"));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(new User()));

        final RedirectAttributes ra = flash();
        controller.create("A@b.com", false, ra);
        assertEquals("Email \"a@b.com\" is already taken.", error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void refusesToCreateUserWhenSmtpDisabled() {
        // The whole point of the invitation flow: the recipient gets the link by email.
        // No SMTP → no link → refuse the create up front. (The previous flow let admin
        // type a password and skip the email; that's the path we're closing.)
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpDisabled());

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("outbound email is not enabled"),
                "expected SMTP-disabled error, got: " + error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never()).sendInvitation(anyString(), anyString());
    }

    @Test
    void issuesInvitationAndAuditsOnHappyPath() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpEnabled());
        stubIssue("a@b.com", false);
        when(userNotificationService.buildInvitationLink(eq("tok-12345")))
                .thenReturn("https://arbiter.example/invitations/tok-12345");
        when(userNotificationService.sendInvitation(eq("a@b.com"),
                eq("https://arbiter.example/invitations/tok-12345"))).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, ra);

        // Invitation issued, link built from token, email sent — and an audit row written
        // referencing the invitation id (NOT the user id, since no user exists yet).
        verify(invitationService).issue(eq("a@b.com"), eq(false), anySet());
        verify(userNotificationService).buildInvitationLink("tok-12345");
        verify(userNotificationService).sendInvitation("a@b.com",
                "https://arbiter.example/invitations/tok-12345");
        verify(auditLogService).log(eq("USER_INVITATION_ISSUED"), eq("Invitation"),
                eq("inv-1"), any());
        // No user row created up front — that happens at redemption time.
        verify(userRepository, never()).save(any());
        assertTrue(success(ra).contains("Invitation sent"));
    }

    @Test
    void invitationEmailFailureSurfacedAsError() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpEnabled());
        stubIssue("a@b.com", true);
        when(userNotificationService.buildInvitationLink(anyString())).thenReturn("https://x/inv/tok");
        when(userNotificationService.sendInvitation(anyString(), anyString())).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", true, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("could not be sent"));
        // Don't audit a successful issuance if the email never went out.
        verify(auditLogService, never()).log(eq("USER_INVITATION_ISSUED"), any(), any(), any());
    }

    @Test
    void emailIsNormalizedToLowerCaseBeforeIssuing() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpEnabled());
        stubIssue("a@b.com", false);
        when(userNotificationService.buildInvitationLink(anyString())).thenReturn("link");
        when(userNotificationService.sendInvitation(anyString(), anyString())).thenReturn(true);

        controller.create("  A@B.COM ", false, flash());

        verify(invitationService).issue(eq("a@b.com"), eq(false), anySet());
        verify(userRepository).findByEmail("a@b.com");
    }

    // ---------- /admin/users/{id}/unlock ----------

    @Test
    void unlockClearsLockoutAndAudits() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            loginAttemptService.onFailure("alice@x.com", "1.1.1.1");
            loginAttemptService.onFailure("alice@x.com", "2.2.2.2");
        }
        assertTrue(loginAttemptService.isEmailLocked("alice@x.com"));

        final User u = new User();
        u.setId("u-1");
        u.setEmail("alice@x.com");
        when(userRepository.findById("u-1")).thenReturn(Optional.of(u));

        final RedirectAttributes ra = flash();
        final org.springframework.security.core.Authentication adminAuth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin@x.com", null,
                        java.util.Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        controller.unlock("u-1", adminAuth, ra);

        assertFalse(loginAttemptService.isEmailLocked("alice@x.com"));
        assertTrue(success(ra).contains("alice@x.com"));
        verify(auditLogService).log(eq("USER_UNLOCK"), eq("User"), eq("u-1"), any());
    }

    @Test
    void unlockUnknownUserReportsError() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.unlock("ghost", null, ra);
        assertEquals("User not found.", error(ra));
        verify(auditLogService, never()).log(eq("USER_UNLOCK"), any(), any(), any());
    }
}
