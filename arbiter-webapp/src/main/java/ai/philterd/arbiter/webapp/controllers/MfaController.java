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
import ai.philterd.arbiter.webapp.security.LoginAttemptService;
import ai.philterd.arbiter.webapp.security.MfaAuthenticationSuccessHandler;
import ai.philterd.arbiter.webapp.security.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/mfa")
public class MfaController {

    private final TotpService totpService;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final LoginAttemptService loginAttemptService;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public MfaController(final TotpService totpService,
                         final UserRepository userRepository,
                         final SecurityContextRepository securityContextRepository,
                         final LoginAttemptService loginAttemptService) {
        this.totpService = totpService;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @GetMapping
    public String showMfaChallenge(final HttpSession session, final Model model) {
        if (session.getAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH) == null) {
            return "redirect:/login";
        }
        if (Boolean.TRUE.equals(session.getAttribute("mfaError"))) {
            model.addAttribute("error", "Invalid code. Please try again.");
            session.removeAttribute("mfaError");
        }
        return "mfa";
    }

    @PostMapping
    public String verifyMfaCode(@RequestParam("code") final String code,
                                final HttpSession session,
                                final HttpServletRequest request,
                                final HttpServletResponse response) {
        final Authentication pending =
                (Authentication) session.getAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
        if (pending == null) {
            return "redirect:/login";
        }

        final String email = pending.getName();
        final String ip = request.getRemoteAddr();

        // The lock counter is shared with the password stage. If the (email, ip) pair was
        // already locked when the user reaches /mfa (e.g. by failing TOTP repeatedly in a
        // prior session), abandon the pending challenge and force them back to /login.
        if (loginAttemptService.isLocked(email, ip)) {
            session.removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
            session.removeAttribute("mfaError");
            return "redirect:/login?locked";
        }

        final User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !totpService.verify(user.getTotpSecret(), code)) {
            loginAttemptService.onFailure(email, ip);
            // If this last failure pushed us over the threshold, drop the pending session
            // so the attacker can't keep the form open and continue.
            if (loginAttemptService.isLocked(email, ip)) {
                session.removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
                session.removeAttribute("mfaError");
                return "redirect:/login?locked";
            }
            session.setAttribute("mfaError", true);
            return "redirect:/mfa";
        }

        loginAttemptService.onSuccess(email, ip);
        session.removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
        session.removeAttribute("mfaError");

        final SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(pending);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return "redirect:/";
    }
}
