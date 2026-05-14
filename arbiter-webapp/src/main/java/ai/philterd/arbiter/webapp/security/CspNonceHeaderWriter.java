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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Writes a per-request {@code Content-Security-Policy} header with a freshly-minted
 * nonce, and exposes the nonce on the request as the {@code cspNonce} attribute so
 * Thymeleaf templates can stamp matching {@code nonce="…"} on their inline
 * {@code <script>} blocks via {@code th:nonce="${cspNonce}"}.
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
public class CspNonceHeaderWriter implements HeaderWriter {

    /** Request attribute name that Thymeleaf templates read for {@code th:nonce}. */
    public static final String ATTRIBUTE_NAME = "cspNonce";

    private static final String HEADER = "Content-Security-Policy";
    private static final int NONCE_BYTES = 16;            // 128 bits — CSP3 minimum
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    @Override
    public void writeHeaders(final HttpServletRequest request, final HttpServletResponse response) {
        // Same nonce for the entire request — multiple inline blocks on one page share it.
        // If the attribute is already populated by an earlier writer in the chain, reuse it
        // rather than minting a second nonce that would only match half the page.
        String nonce = (String) request.getAttribute(ATTRIBUTE_NAME);
        if (nonce == null) {
            final byte[] bytes = new byte[NONCE_BYTES];
            random.nextBytes(bytes);
            nonce = ENCODER.encodeToString(bytes);
            request.setAttribute(ATTRIBUTE_NAME, nonce);
        }

        // Static error pages and Spring Security's own /error handler may have already
        // written a CSP — replace, don't append, so the policy is unambiguous.
        response.setHeader(HEADER, build(nonce));
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
