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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/mfa")
public class MfaController {

    private final TotpService totpService;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public MfaController(final TotpService totpService,
                         final UserRepository userRepository,
                         final SecurityContextRepository securityContextRepository) {
        this.totpService = totpService;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
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

        final User user = userRepository.findByEmail(pending.getName()).orElse(null);
        if (user == null || !totpService.verify(user.getTotpSecret(), code)) {
            session.setAttribute("mfaError", true);
            return "redirect:/mfa";
        }

        session.removeAttribute(MfaAuthenticationSuccessHandler.PENDING_MFA_AUTH);
        session.removeAttribute("mfaError");

        final SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(pending);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return "redirect:/";
    }
}
