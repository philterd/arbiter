/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;

public class MfaAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String PENDING_MFA_AUTH = "PENDING_MFA_AUTH";

    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SavedRequestAwareAuthenticationSuccessHandler delegate;

    public MfaAuthenticationSuccessHandler(final UserRepository userRepository,
                                           final SecurityContextRepository securityContextRepository) {
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        this.delegate.setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final Authentication authentication) throws IOException, ServletException {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user != null && user.isMfaEnabled() && user.getTotpSecret() != null) {
            final HttpSession session = request.getSession(true);
            session.setAttribute(PENDING_MFA_AUTH, authentication);

            // Clear the security context so the user is not considered logged in yet.
            final SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(emptyContext);
            securityContextRepository.saveContext(emptyContext, request, response);

            response.sendRedirect(request.getContextPath() + "/mfa");
        } else {
            delegate.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
