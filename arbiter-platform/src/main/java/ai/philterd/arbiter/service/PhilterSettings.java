/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.philter.PhilterClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * The one Philter this Arbiter works with: it signs people in, holds the policies, and redacts. Its
 * Philter must run with {@code PASSWORD_SIGN_IN_ENABLED=true}.
 * Read from the environment only ({@code ARBITER_PHILTER_URL}, {@code ARBITER_PHILTER_API_KEY}), with
 * TLS from {@code arbiter.philter.tls.*}, so a bad value stops Arbiter at startup.
 *
 * <p>The API key is the one background work such as ingest uses, where no person is signed in.
 * It never leaves this class except as a bearer token to Philter.
 */
@Component
public class PhilterSettings {

    private final String url;
    private final String apiKey;
    private final PhilterTlsConfig tls;
    private volatile PhilterClient client;

    // Whether the API key belongs to a Philter administrator, as Philter last said; null until checked.
    private volatile Boolean apiKeyAdmin;

    public PhilterSettings(final String url, final String apiKey, final PhilterTlsConfig tls) {
        this(url, apiKey, null, tls);
    }

    @Autowired
    public PhilterSettings(@Value("${arbiter.philter.url:}") final String url,
                           @Value("${arbiter.philter.api-key:}") final String apiKey,
                           @Value("${arbiter.sign-in.mode:}") final String signInMode,
                           final PhilterTlsConfig tls) {
        // ARBITER_SIGN_IN_MODE is no longer read. Refuse "local" rather than ignore it, so a deployment
        // expecting Arbiter's own accounts is told they are gone instead of finding no one can sign in.
        if (signInMode != null && "local".equalsIgnoreCase(signInMode.trim())) {
            throw new IllegalStateException("ARBITER_SIGN_IN_MODE=local is no longer supported: people sign in "
                    + "through Philter only. Remove ARBITER_SIGN_IN_MODE; see the upgrade notes in Arbiter's "
                    + "security docs.");
        }
        this.url = normalizeUrl(url);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ARBITER_PHILTER_API_KEY is required: the API key Arbiter uses for "
                    + "background work such as ingest, for a Philter user who is not an administrator.");
        }
        this.apiKey = apiKey.trim();
        this.tls = tls;
    }

    /** Philter's base URL, without a trailing slash. */
    public String url() {
        return url;
    }

    /** An SDK client for Philter, authenticated with the API key. */
    public PhilterClient client() {
        PhilterClient c = client;
        if (c == null) {
            c = PhilterClients.build(url, apiKey, tls);
            client = c;
        }
        return c;
    }

    /** The API key, for the redaction client factory. */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Whether the API key belongs to a Philter administrator, from the caller's role in
     * {@code GET /api/limits}, which any key may call whatever its scopes. Null when Philter does not
     * say. The answer is kept for {@link #apiKeyAdmin()}.
     */
    public Boolean checkKeyRole() throws IOException {
        final ai.philterd.philter.model.Limits limits = client().getLimits();
        final String role = limits == null || limits.getCaller() == null ? null : limits.getCaller().getRole();
        final Boolean admin = role == null ? null : "admin".equalsIgnoreCase(role);
        apiKeyAdmin = admin;
        return admin;
    }

    /** What {@link #checkKeyRole()} last found: true for an administrator's key, null if not yet known. */
    public Boolean apiKeyAdmin() {
        return apiKeyAdmin;
    }

    /**
     * {@code value} as an http or https base URL without a trailing slash.
     *
     * @throws IllegalStateException naming {@code ARBITER_PHILTER_URL} if it is missing or not one
     */
    static String normalizeUrl(final String value) {
        final String trimmed = value == null ? "" : value.trim();
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ARBITER_PHILTER_URL is not a valid URL.");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("ARBITER_PHILTER_URL is required: the http or https URL of the "
                    + "Philter that Arbiter uses, such as http://philter:8080.");
        }
        if (uri.getUserInfo() != null) {
            // It would be logged at startup; credentials belong in ARBITER_PHILTER_API_KEY.
            throw new IllegalStateException("ARBITER_PHILTER_URL must not contain a user name or password.");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
