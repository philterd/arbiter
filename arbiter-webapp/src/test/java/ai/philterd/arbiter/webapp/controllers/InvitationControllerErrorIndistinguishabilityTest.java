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
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.webapp.security.InvitationService;
import ai.philterd.arbiter.webapp.security.InvitationService.RedemptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins finding #8's "single rejection message" contract for {@link InvitationController}.
 * Three previously distinct user-facing messages — invalid token, already-consumed token,
 * expired token — let a probing caller distinguish which of the three states a given token
 * fell into. 32 random-byte tokens make this hard to exploit today, but eliminating the
 * oracle is cheap and forward-compatible with any future tightening of the token format.
 *
 * <p>These tests exercise the GET path (the landing page after the recipient clicks the
 * email link) and the POST path (form submission against an expired/invalid/consumed
 * token) and assert that both produce the same body, regardless of which sub-state
 * triggered the rejection.
 */
class InvitationControllerErrorIndistinguishabilityTest {

    private static final String UNIFORM_ERROR =
            "This invitation link is no longer valid. Ask your administrator to send a new one.";

    private InvitationService invitationService;
    private AuditLogService auditLogService;
    private InvitationController controller;

    @BeforeEach
    void setUp() {
        invitationService = mock(InvitationService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new InvitationController(invitationService, auditLogService);
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final Model model) {
        final Object e = model.asMap().get("error");
        return e == null ? null : e.toString();
    }

    private static Invitation pending(final String email, final Instant expiresAt) {
        final Invitation i = new Invitation();
        i.setId("inv-1");
        i.setEmail(email);
        i.setExpiresAt(expiresAt);
        return i;
    }

    private static Invitation consumed(final String email) {
        final Invitation i = pending(email, Instant.now().plusSeconds(3600));
        i.setConsumedAt(Instant.now());
        return i;
    }

    private static Invitation expired(final String email) {
        // Expired one second ago.
        return pending(email, Instant.now().minusSeconds(1));
    }

    // ===================================================================
    // GET /invitations/{token}
    // ===================================================================

    @Test
    void showInvalidTokenReturnsUniformError() {
        when(invitationService.findByToken("nope")).thenReturn(Optional.empty());

        final Model model = new ConcurrentModel();
        final String view = controller.show("nope", model);

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void showConsumedTokenReturnsUniformError() {
        when(invitationService.findByToken("consumed-token"))
                .thenReturn(Optional.of(consumed("alice@x.com")));

        final Model model = new ConcurrentModel();
        final String view = controller.show("consumed-token", model);

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void showExpiredTokenReturnsUniformError() {
        when(invitationService.findByToken("expired-token"))
                .thenReturn(Optional.of(expired("alice@x.com")));

        final Model model = new ConcurrentModel();
        final String view = controller.show("expired-token", model);

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void allThreeRejectStatesProduceByteIdenticalBody() {
        // The actual contract: a probing caller cannot tell which of the three states
        // their token was in by reading the response body. This is the F4/#5/#11
        // pattern applied to the invitation surface.
        when(invitationService.findByToken("invalid")).thenReturn(Optional.empty());
        when(invitationService.findByToken("consumed"))
                .thenReturn(Optional.of(consumed("alice@x.com")));
        when(invitationService.findByToken("expired"))
                .thenReturn(Optional.of(expired("alice@x.com")));

        final Model m1 = new ConcurrentModel();
        final Model m2 = new ConcurrentModel();
        final Model m3 = new ConcurrentModel();
        final String v1 = controller.show("invalid", m1);
        final String v2 = controller.show("consumed", m2);
        final String v3 = controller.show("expired", m3);

        assertEquals(v1, v2);
        assertEquals(v2, v3);
        assertEquals(error(m1), error(m2));
        assertEquals(error(m2), error(m3));
        assertNotNull(error(m1));
    }

    @Test
    void showValidPendingTokenStillRendersTheForm() {
        // Sanity: the happy path is not affected. A live, pending invitation renders
        // the password-set form, not the uniform error page.
        when(invitationService.findByToken("good-token"))
                .thenReturn(Optional.of(pending("alice@x.com", Instant.now().plusSeconds(3600))));

        final Model model = new ConcurrentModel();
        final String view = controller.show("good-token", model);

        assertEquals("invitation", view);
        assertEquals("alice@x.com", model.asMap().get("email"));
        assertEquals("good-token", model.asMap().get("token"));
    }

    // ===================================================================
    // POST /invitations/{token}
    // ===================================================================

    @Test
    void redeemInvalidTokenReturnsUniformError() {
        when(invitationService.findByToken("invalid")).thenReturn(Optional.empty());
        when(invitationService.redeem(anyString(), anyString()))
                .thenReturn(RedemptionStatus.INVALID_TOKEN);

        final Model model = new ConcurrentModel();
        final String view = controller.redeem("invalid", "newPassword123",
                "newPassword123", model, flash());

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void redeemAlreadyConsumedReturnsUniformError() {
        when(invitationService.findByToken("consumed"))
                .thenReturn(Optional.of(consumed("alice@x.com")));
        when(invitationService.redeem(anyString(), anyString()))
                .thenReturn(RedemptionStatus.ALREADY_REDEEMED);

        final Model model = new ConcurrentModel();
        final String view = controller.redeem("consumed", "newPassword123",
                "newPassword123", model, flash());

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void redeemExpiredReturnsUniformError() {
        when(invitationService.findByToken("expired"))
                .thenReturn(Optional.of(expired("alice@x.com")));
        when(invitationService.redeem(anyString(), anyString()))
                .thenReturn(RedemptionStatus.EXPIRED);

        final Model model = new ConcurrentModel();
        final String view = controller.redeem("expired", "newPassword123",
                "newPassword123", model, flash());

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }

    @Test
    void redeemEmailAlreadyTakenAlsoReturnsUniformError() {
        // A leaked-then-replayed invitation, after a User row was created out-of-band
        // for the same email, used to leak the email's registered status here. Now
        // it falls through to the same uniform message.
        when(invitationService.findByToken("good"))
                .thenReturn(Optional.of(pending("alice@x.com", Instant.now().plusSeconds(3600))));
        when(invitationService.redeem(anyString(), anyString()))
                .thenReturn(RedemptionStatus.EMAIL_ALREADY_TAKEN);

        final Model model = new ConcurrentModel();
        final String view = controller.redeem("good", "newPassword123",
                "newPassword123", model, flash());

        assertEquals("invitation-error", view);
        assertEquals(UNIFORM_ERROR, error(model));
    }
}
