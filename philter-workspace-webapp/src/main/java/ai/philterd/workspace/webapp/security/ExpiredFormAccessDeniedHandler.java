/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * Sends a form posted by someone who is not signed in, with a CSRF token their session does not hold, back to the
 * sign-in page instead of a bare 403. With a session cookie, that is a sign-in page left open while the session
 * expired or Philter Workspace restarted ({@code ?expired}). With none, the browser did not keep the session
 * cookie, usually because it is {@code Secure} and the page was served over plain HTTP ({@code ?cookie}), and
 * signing in cannot work until that changes. Every other refusal, including a bad token from someone signed in,
 * is the usual 403.
 */
public class ExpiredFormAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    @Override
    public void handle(final HttpServletRequest request, final HttpServletResponse response,
                       final AccessDeniedException exception) throws IOException, ServletException {
        final String path = request.getRequestURI().substring(request.getContextPath().length());
        if (exception instanceof CsrfException && !path.startsWith("/api/") && !signedIn()) {
            final String reason = request.getRequestedSessionId() == null ? "cookie" : "expired";
            response.sendRedirect(request.getContextPath() + "/login?" + reason);
            return;
        }
        fallback.handle(request, response, exception);
    }

    private static boolean signedIn() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
