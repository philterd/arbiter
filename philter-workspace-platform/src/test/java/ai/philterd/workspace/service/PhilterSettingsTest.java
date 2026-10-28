/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.philter.PhilterTlsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhilterSettingsTest {

    @Test
    void readsTheUrlWithoutATrailingSlash() {
        final PhilterSettings settings = new PhilterSettings(" https://philter.internal:8080/ ", " sk_key ",
                PhilterTlsConfig.none());
        assertEquals("https://philter.internal:8080", settings.url());
        assertEquals("sk_key", settings.apiKey());
        assertNull(settings.apiKeyAdmin(), "not checked until Philter is asked");
    }

    @Test
    void refusesAMissingOrUnusableUrl() {
        for (final String url : new String[] {"", "philter:8080", "ftp://philter", "http://user:pass@philter:8080"}) {
            assertThrows(IllegalStateException.class, () -> new PhilterSettings(url, "sk_key", PhilterTlsConfig.none()),
                    url);
        }
    }

    @Test
    void refusesAMissingApiKeyWithoutNamingAnyValue() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PhilterSettings("http://philter:8080", " ", PhilterTlsConfig.none()));
        assertFalse(e.getMessage().contains("philter:8080"));
    }

    @Test
    void refusesTheRemovedLocalSignInMode() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PhilterSettings("http://philter:8080", "sk_key", " Local ", PhilterTlsConfig.none()));
        assertTrue(e.getMessage().contains("PHILTER_WORKSPACE_SIGN_IN_MODE=local"));
        // The old Philter value, or none, is accepted and ignored.
        new PhilterSettings("http://philter:8080", "sk_key", "philter", PhilterTlsConfig.none());
        new PhilterSettings("http://philter:8080", "sk_key", "", PhilterTlsConfig.none());
    }
}
