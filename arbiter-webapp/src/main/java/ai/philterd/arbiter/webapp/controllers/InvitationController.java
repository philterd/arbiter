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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.Optional;

/**
 * Public, unauthenticated controller backing the {@code /invitations/{token}} email link.
 * Recipients land on a small set-password form, submit, and are redirected to the login
 * page. The invitation is single-shot; the token never returns to the database in
 * plaintext. See {@link InvitationService} for the underlying validation rules.
 */
@Controller
@RequestMapping("/invitations")
public class InvitationController {

    private final InvitationService invitationService;
    private final AuditLogService auditLogService;

    public InvitationController(final InvitationService invitationService,
                                final AuditLogService auditLogService) {
        this.invitationService = invitationService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/{token}")
    public String show(@PathVariable final String token, final Model model) {
        final Optional<Invitation> opt = invitationService.findByToken(token);
        if (opt.isEmpty()) {
            model.addAttribute("error",
                    "This invitation link is invalid. Ask your administrator to send a new one.");
            return "invitation-error";
        }
        final Invitation invite = opt.get();
        if (invite.getConsumedAt() != null) {
            model.addAttribute("error",
                    "This invitation has already been redeemed. Sign in with the password you set.");
            return "invitation-error";
        }
        if (invite.getExpiresAt() != null
                && !java.time.Instant.now().isBefore(invite.getExpiresAt())) {
            model.addAttribute("error",
                    "This invitation has expired. Ask your administrator to send a new one.");
            return "invitation-error";
        }
        model.addAttribute("token", token);
        model.addAttribute("email", invite.getEmail());
        return "invitation";
    }

    @PostMapping("/{token}")
    public String redeem(@PathVariable final String token,
                         @RequestParam("password") final String password,
                         @RequestParam("confirmPassword") final String confirmPassword,
                         final Model model,
                         final RedirectAttributes redirectAttributes) {
        // Echo the email back to the form on validation failure so the user doesn't see a
        // bare "password mismatch" page with no context.
        final Optional<Invitation> opt = invitationService.findByToken(token);
        final String email = opt.map(Invitation::getEmail).orElse(null);
        model.addAttribute("token", token);
        model.addAttribute("email", email);

        if (password == null || !password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match.");
            return "invitation";
        }
        if (password.length() < 12) {
            model.addAttribute("error", "Password must be at least 12 characters.");
            return "invitation";
        }

        final RedemptionStatus result = invitationService.redeem(token, password);
        switch (result) {
            case OK:
                if (email != null) {
                    auditLogService.log("USER_INVITATION_REDEEMED", "User", email,
                            Map.of("email", email));
                }
                redirectAttributes.addFlashAttribute("success",
                        "Your password is set. You can sign in now.");
                return "redirect:/login?invited";
            case INVALID_TOKEN:
                model.addAttribute("error",
                        "This invitation link is invalid. Ask your administrator to send a new one.");
                return "invitation-error";
            case ALREADY_REDEEMED:
                model.addAttribute("error",
                        "This invitation has already been redeemed. Sign in with the password you set.");
                return "invitation-error";
            case EXPIRED:
                model.addAttribute("error",
                        "This invitation has expired. Ask your administrator to send a new one.");
                return "invitation-error";
            case EMAIL_ALREADY_TAKEN:
                model.addAttribute("error",
                        "An account already exists for that email. Use the sign-in page instead.");
                return "invitation-error";
            case WEAK_PASSWORD:
            default:
                model.addAttribute("error", "Password must be at least 12 characters.");
                return "invitation";
        }
    }
}
