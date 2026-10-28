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

import ai.philterd.philter.model.StatusResponse;
import ai.philterd.philter.model.exceptions.ClientException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether Arbiter supports the Philter it signs people in to, from the version
 * {@code GET /api/health} reports. Checked on the sign-in page and at each sign-in rather than at startup,
 * so Arbiter still starts while Philter is down and notices an upgraded Philter without a restart.
 */
public final class PhilterVersions {

    /** The Philter major version this release of Arbiter supports. Keep the docs' list in step. */
    public static final int SUPPORTED_MAJOR = 4;

    static final String OLDER_THAN_4 = "This Philter is older than 4.0, which Arbiter does not support. "
            + "Arbiter supports Philter " + SUPPORTED_MAJOR + ".x.";

    private static final Pattern MAJOR = Pattern.compile("^(\\d+)\\.");

    private PhilterVersions() {
    }

    /**
     * Why Arbiter cannot sign people in to this Philter, or null when it can, or when Philter's version
     * cannot be read; then sign-in goes ahead and reports any failure itself.
     */
    public static String unsupported(final PhilterSignInService signInService) {
        final StatusResponse health;
        try {
            health = signInService.health();
        } catch (ClientException e) {
            // Philter 3.x has no /api/health (it had /api/status).
            return e.getStatusCode() == 404 ? OLDER_THAN_4 : null;
        } catch (Exception e) {
            return null;
        }
        return health == null ? null : unsupported(health.getApplicationVersion());
    }

    public static String unsupported(final String version) {
        if (version == null) {
            return null;
        }
        final Matcher matcher = MAJOR.matcher(version.trim());
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) == SUPPORTED_MAJOR) {
            return null;
        }
        return "This Philter is version " + version.trim() + ", which Arbiter does not support. "
                + "Arbiter supports Philter " + SUPPORTED_MAJOR + ".x.";
    }
}
