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
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.session.SessionRegistry;
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
    private ai.philterd.arbiter.repository.InvitationRepository invitationRepository;
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
        invitationRepository = mock(ai.philterd.arbiter.repository.InvitationRepository.class);
        // Default: no pending invitations for any email. Tests that exercise the
        // "pending invitation supersedes" path override this explicitly.
        when(invitationRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        sessionRegistry = mock(SessionRegistry.class);
        controller = new AdminController(userRepository, passwordEncoder, auditLogService,
                notificationSettingsService, userNotificationService,
                loginAttemptService, invitationService, invitationRepository, sessionRegistry);
    }

    private SessionRegistry sessionRegistry;

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
        controller.create(" ", false, null, ra);
        assertEquals("Email address is required.", error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never()).sendInvitation(anyString(), anyString());
    }

    @Test
    void rejectsInvalidEmail() {
        final RedirectAttributes ra = flash();
        controller.create("not-an-email", false, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid email address"));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void rejectsEmailWithEmbeddedNewlineLogForgeryAttempt() {
        // Finding #3: a smuggled \n in the email field would let the admin forge
        // log lines. The redemption-success log later prints "Redeemed invitation
        // for {email}" — without this check, a value like
        // "attacker@x.com\n2026-... INFO Redeemed for ceo@arbiter.local"
        // would split into two indistinguishable log records. Same vector for
        // the audit "details" map. Refuse at the controller boundary.
        final RedirectAttributes ra = flash();
        controller.create("attacker@x.com\n2026-05-13 INFO forged-line", false, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid email address"),
                "control-char email must produce the same generic error as any malformed input "
                        + "(so a probing admin can't fingerprint the rule)");
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void rejectsEmailWithEmbeddedCarriageReturnAndTab() {
        // Belt-and-braces: the rule is "any character < 0x20 or == 0x7F", not
        // just \n. Spot-check the other common control chars that could split
        // log lines or smuggle into other downstream strings.
        for (String injection : new String[]{
                "attacker@x.com\r2026-05-13 INFO forged",
                "attacker@x.com\tforged",
                "attacker@x.comforged"}) {
            final RedirectAttributes ra = flash();
            controller.create(injection, false, null, ra);
            assertNotNull(error(ra), "expected rejection for: " + injection.replace("\r", "\\r")
                    .replace("\t", "\\t").replace("", "\\u007f"));
            assertTrue(error(ra).contains("not a valid email address"));
        }
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(new User()));

        final RedirectAttributes ra = flash();
        controller.create("A@b.com", false, null, ra);
        assertEquals("Email \"a@b.com\" is already taken.", error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
    }

    @Test
    void rejectsEmailWithOutstandingPendingInvitation() {
        // Admin B has already invited the recipient — the invitation row is pending
        // (consumedAt is null). Admin A's re-issue must be refused with the same
        // generic "Email is already taken." that an existing User row produces.
        // Two doors close here at once: A can't enumerate which addresses B has
        // already invited (oracle), and B's emailed link isn't silently superseded
        // (loss of UX).
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
        final ai.philterd.arbiter.model.Invitation pending = new ai.philterd.arbiter.model.Invitation();
        pending.setId("inv-existing");
        pending.setEmail("a@b.com");
        pending.setConsumedAt(null);
        when(invitationRepository.findByEmail("a@b.com")).thenReturn(Optional.of(pending));

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, null, ra);

        assertEquals("Email \"a@b.com\" is already taken.", error(ra),
                "pending-invitation rejection must use the same body as a real duplicate");
        // No new invitation issued — B's pending row stays the live token.
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never())
                .sendInvitation(anyString(), anyString());
        verify(auditLogService, never())
                .log(eq("USER_INVITATION_ISSUED"),
                        any(), any(), any());
    }

    @Test
    void consumedInvitationDoesNotBlockNewCreate() {
        // A consumed invitation row stays in the collection for audit-trail purposes;
        // it must not block a fresh create for the same email (e.g. the user was later
        // deleted and is being re-invited). The check only blocks *pending* invitations.
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
        final ai.philterd.arbiter.model.Invitation consumed = new ai.philterd.arbiter.model.Invitation();
        consumed.setId("inv-old");
        consumed.setEmail("a@b.com");
        consumed.setConsumedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        when(invitationRepository.findByEmail("a@b.com")).thenReturn(Optional.of(consumed));

        final ai.philterd.arbiter.model.NotificationSettings settings =
                new ai.philterd.arbiter.model.NotificationSettings();
        settings.setEnabled(true);
        when(notificationSettingsService.load()).thenReturn(settings);
        final ai.philterd.arbiter.model.Invitation issued = new ai.philterd.arbiter.model.Invitation();
        issued.setId("inv-new");
        when(invitationService.issue(eq("a@b.com"), eq(false), anySet()))
                .thenReturn(new InvitationService.IssuedInvitation(issued, "fresh-token"));
        when(userNotificationService.buildInvitationLink(eq("fresh-token")))
                .thenReturn("https://arbiter/invitations/fresh-token");
        when(userNotificationService.sendInvitation(anyString(), anyString())).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, null, ra);

        verify(invitationService).issue(eq("a@b.com"), eq(false), anySet());
        verify(userNotificationService).sendInvitation(eq("a@b.com"),
                eq("https://arbiter/invitations/fresh-token"));
        assertNotNull(success(ra));
    }

    @Test
    void refusesToCreateUserWhenSmtpDisabledAndNoInitialPasswordGiven() {
        // The email-invitation path requires SMTP. With SMTP disabled and the
        // admin not supplying an initial password, the create is refused — and
        // the error message points the admin at the SMTP-free alternative.
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpDisabled());

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("outbound email is not enabled"),
                "expected SMTP-disabled error, got: " + error(ra));
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never()).sendInvitation(anyString(), anyString());
    }

    @Test
    void createWithInitialPasswordSkipsSmtpAndForcesRotationOnFirstLogin() {
        // SMTP-free admin add: an initial password is supplied so the create
        // succeeds even with SMTP disabled, and the saved user is flagged
        // mustChangePassword so the user can't keep the credential the admin
        // chose for them past first sign-in.
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(notificationSettingsService.load()).thenReturn(smtpDisabled());

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, "correct-horse-battery-staple", ra);

        assertNotNull(success(ra));
        assertTrue(success(ra).toLowerCase().contains("out-of-band"),
                "expected out-of-band hand-off message, got: " + success(ra));
        // No invitation was issued, no email was sent, no SMTP was consulted.
        verify(invitationService, never()).issue(anyString(), anyBoolean(), anySet());
        verify(userNotificationService, never()).sendInvitation(anyString(), anyString());
        // The new user is persisted with the encoded password and the rotation flag.
        final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        final User saved = captor.getValue();
        assertEquals("a@b.com", saved.getEmail());
        assertEquals("hash", saved.getPasswordHash());
        assertTrue(saved.isMustChangePassword(),
                "newly-created accounts with admin-set passwords must rotate at first login");
    }

    @Test
    void createWithInitialPasswordRejectsShortPassword() {
        // Mirrors the 12-char minimum enforced by /settings/password so the
        // admin can't bypass the policy by choosing the user's password.
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.create("a@b.com", false, "short", ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("at least 12 characters"));
        verify(userRepository, never()).save(any());
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
        controller.create("a@b.com", false, null, ra);

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
        controller.create("a@b.com", true, null, ra);

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

        controller.create("  A@B.COM ", false, null, flash());

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

    // ---------- /admin/users/{id}/edit — self-edit and last-admin guards ----------

    private static org.springframework.security.core.Authentication adminAuth(final String email) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                email, null,
                java.util.Set.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static User userWith(final String id, final String email, final boolean admin) {
        final User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setRoles(admin ? java.util.Set.of("ADMIN") : java.util.Set.of("USER"));
        return u;
    }

    @Test
    void editRefusesSelf() {
        final User self = userWith("u-self", "admin@x.com", true);
        when(userRepository.findById("u-self")).thenReturn(Optional.of(self));

        final RedirectAttributes ra = flash();
        controller.edit("u-self", "USER", null, adminAuth("admin@x.com"), ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("cannot edit your own account"),
                "expected self-edit refusal, got: " + error(ra));
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).countByRolesContaining(anyString());
        verify(auditLogService, never()).log(eq("USER_UPDATE"), any(), any(), any());
    }

    @Test
    void editRefusesDemotingLastAdmin() {
        // Different admin demoting the only remaining ADMIN — would leave 0 admins.
        final User target = userWith("u-1", "alice@x.com", true);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));
        when(userRepository.countByRolesContaining("ADMIN")).thenReturn(1L);

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "USER", null, adminAuth("admin@x.com"), ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("at least one administrator"),
                "expected last-admin refusal, got: " + error(ra));
        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("USER_UPDATE"), any(), any(), any());
    }

    @Test
    void editRefusesConvertingLastAdminToAuditor() {
        // The last-admin guard treats AUDITOR as "no longer admin" — converting the only
        // remaining ADMIN to AUDITOR also leaves the system with zero admins.
        final User target = userWith("u-1", "alice@x.com", true);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));
        when(userRepository.countByRolesContaining("ADMIN")).thenReturn(1L);

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "AUDITOR", null, adminAuth("admin@x.com"), ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("at least one administrator"),
                "expected last-admin refusal even when target role is AUDITOR, got: " + error(ra));
        verify(userRepository, never()).save(any());
    }

    @Test
    void editAllowsDemotingWhenAnotherAdminRemains() {
        final User target = userWith("u-1", "alice@x.com", true);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));
        when(userRepository.countByRolesContaining("ADMIN")).thenReturn(2L);

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "USER", null, adminAuth("admin@x.com"), ra);

        assertNotNull(success(ra));
        assertFalse(target.getRoles().contains("ADMIN"));
        verify(userRepository).save(target);
        verify(auditLogService).log(eq("USER_UPDATE"), eq("User"), eq("u-1"), any());
    }

    @Test
    void editAllowsPromotingNonAdminWithoutCheckingAdminCount() {
        // Promoting USER → ADMIN never reduces the admin pool, so the count check is skipped.
        final User target = userWith("u-1", "bob@x.com", false);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "ADMIN", null, adminAuth("admin@x.com"), ra);

        assertNotNull(success(ra));
        assertTrue(target.getRoles().contains("ADMIN"));
        verify(userRepository).save(target);
        verify(userRepository, never()).countByRolesContaining(anyString());
    }

    @Test
    void editAllowsLeavingAdminUnchangedEvenWhenSoleAdmin() {
        // wasAdmin && admin (no transition) — the count guard only fires on demotion.
        final User target = userWith("u-1", "alice@x.com", true);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "ADMIN", null, adminAuth("admin@x.com"), ra);

        assertNotNull(success(ra));
        verify(userRepository).save(target);
        verify(userRepository, never()).countByRolesContaining(anyString());
    }

    @Test
    void editAssignsAuditorRole() {
        // Promoting USER → AUDITOR is allowed for any non-admin target. AUDITOR doesn't
        // touch the admin pool so the count check is skipped.
        final User target = userWith("u-1", "alice@x.com", false);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "AUDITOR", null, adminAuth("admin@x.com"), ra);

        assertNotNull(success(ra));
        assertTrue(target.getRoles().contains("AUDITOR"));
        assertFalse(target.getRoles().contains("ADMIN"));
        verify(userRepository).save(target);
        verify(userRepository, never()).countByRolesContaining(anyString());
    }

    @Test
    void editRejectsUnknownRoleString() {
        final User target = userWith("u-1", "alice@x.com", false);
        when(userRepository.findById("u-1")).thenReturn(Optional.of(target));

        final RedirectAttributes ra = flash();
        controller.edit("u-1", "WIZARD", null, adminAuth("admin@x.com"), ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("unknown role"),
                "expected unknown-role refusal, got: " + error(ra));
        verify(userRepository, never()).save(any());
    }
}
