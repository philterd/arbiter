/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessionValidationFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

/**
 * A request from a person signed in through Philter, as every session is: the session holds their
 * {@link PhilterSessionAuthentication}, marked as checked with Philter just now so the session check does
 * not call Philter during the test.
 */
final class SignedIn {

    private SignedIn() {
    }

    static RequestPostProcessor signedIn(final String username, final String... roles) {
        return request -> {
            final SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new PhilterSessionAuthentication(username, "enc-session-key",
                    Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            request.getSession().setAttribute(PhilterSessionValidationFilter.VALIDATED_AT,
                    System.currentTimeMillis());
            return request;
        };
    }
}
