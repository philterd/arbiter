/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints a per-request CSP nonce, exposes it as the {@code cspNonce} request
 * attribute (so Thymeleaf templates can stamp it via {@code th:nonce="${cspNonce}"}),
 * and writes a matching {@code Content-Security-Policy} response header.
 *
 * <p>Why a filter rather than a Spring Security {@code HeaderWriter}: the
 * {@code HeaderWriterFilter} invokes its writers when the response is being
 * committed — i.e. <em>after</em> the view has rendered. A writer that sets a
 * request attribute at that point is too late for Thymeleaf, which has already
 * resolved {@code ${cspNonce}} to {@code null} and dropped the {@code nonce}
 * attribute from every {@code <script>} tag. The header gets the nonce, the
 * page doesn't, and the browser refuses every script under
 * {@code 'strict-dynamic'} (which causes {@code 'self'} to be ignored). Running
 * this as a filter places the attribute on the request <em>before</em>
 * controllers run and templates render.
 *
 * <p>Why a nonce policy rather than {@code 'self'} alone: Arbiter ships inline
 * scripts on every interactive page (review, queue, admin pages). Under {@code
 * script-src 'self'} those would be refused by the browser; the predictable
 * operator response would be to add {@code 'unsafe-inline'}, which neutralises
 * the entire XSS defence the CSP was added to provide. A per-request nonce keeps
 * the policy strict for any attacker-injected script (which by definition can't
 * know the nonce) while letting the legitimate inline blocks through.
 *
 * <p>{@code 'strict-dynamic'} lets a nonced script bootstrap further same-origin
 * scripts without each needing its own nonce — useful for the tailwind.js loader
 * pattern and for any future dynamic-import surface.
 *
 * <p>Nonces are 128 bits of {@link SecureRandom} (the CSP3 spec requires at
 * least 128); base64url-encoded without padding for compact emission and HTML
 * attribute safety. A fresh nonce is generated per request so capturing one
 * doesn't let an attacker forge a script for a later request.
 */
public class CspNonceFilter extends OncePerRequestFilter {

    /** Request attribute name that Thymeleaf templates read for {@code th:nonce}. */
    public static final String ATTRIBUTE_NAME = "cspNonce";

    private static final String HEADER = "Content-Security-Policy";
    private static final int NONCE_BYTES = 16;            // 128 bits — CSP3 minimum
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain)
            throws ServletException, IOException {
        final byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        final String nonce = ENCODER.encodeToString(bytes);

        request.setAttribute(ATTRIBUTE_NAME, nonce);
        response.setHeader(HEADER, build(nonce));

        filterChain.doFilter(request, response);
    }

    /**
     * Build the policy. Visible for tests so the exact directive set can be asserted
     * without running the full filter chain.
     */
    public static String build(final String nonce) {
        return "default-src 'self'; "
                + "script-src 'self' 'nonce-" + nonce + "' 'strict-dynamic'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "font-src 'self' data:; "
                + "connect-src 'self'; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "form-action 'self'; "
                + "frame-ancestors 'none'";
    }
}
