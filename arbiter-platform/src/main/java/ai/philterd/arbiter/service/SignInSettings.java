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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * How people sign in: with Arbiter's own accounts ({@code local}, the default) or through Philter's
 * user management API ({@code philter}). Read from the environment only, so an administrator's session
 * cannot change how everyone signs in, and a bad value stops Arbiter at startup.
 */
@Component
public class SignInSettings {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_PHILTER = "philter";

    private final String mode;
    private final String philterUrl;

    public SignInSettings(@Value("${arbiter.sign-in.mode:local}") final String mode,
                          @Value("${arbiter.sign-in.philter-url:}") final String philterUrl) {
        final String normalized = mode == null || mode.isBlank()
                ? MODE_LOCAL : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_LOCAL.equals(normalized) && !MODE_PHILTER.equals(normalized)) {
            throw new IllegalStateException("ARBITER_SIGN_IN_MODE must be 'local' or 'philter'.");
        }
        this.mode = normalized;
        this.philterUrl = MODE_PHILTER.equals(normalized) ? requireUrl(philterUrl) : null;
    }

    public boolean isPhilter() {
        return MODE_PHILTER.equals(mode);
    }

    public String mode() {
        return mode;
    }

    /** The base URL of the Philter that signs people in, without a trailing slash; null in local mode. */
    public String philterUrl() {
        return philterUrl;
    }

    private static String requireUrl(final String value) {
        final String trimmed = value == null ? "" : value.trim();
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ARBITER_SIGN_IN_PHILTER_URL is not a valid URL.");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("ARBITER_SIGN_IN_MODE=philter requires ARBITER_SIGN_IN_PHILTER_URL, "
                    + "the http or https URL of the Philter that signs people in.");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
