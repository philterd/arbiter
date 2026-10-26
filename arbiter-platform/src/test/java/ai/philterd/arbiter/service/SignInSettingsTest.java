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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInSettingsTest {

    @Test
    void localIsTheDefault() {
        final SignInSettings settings = new SignInSettings("", "");
        assertFalse(settings.isPhilter());
        assertEquals("local", settings.mode());
        assertNull(settings.philterUrl());
    }

    @Test
    void philterModeNeedsTheSignInPhilterUrl() {
        assertThrows(IllegalStateException.class, () -> new SignInSettings("philter", ""));
        assertThrows(IllegalStateException.class, () -> new SignInSettings("philter", "philter:8080"));
        assertThrows(IllegalStateException.class, () -> new SignInSettings("philter", "ftp://philter"));
    }

    @Test
    void philterModeKeepsTheUrlWithoutATrailingSlash() {
        final SignInSettings settings = new SignInSettings(" Philter ", "https://philter.internal:8080/");
        assertTrue(settings.isPhilter());
        assertEquals("https://philter.internal:8080", settings.philterUrl());
    }

    @Test
    void unknownModeStopsStartup() {
        assertThrows(IllegalStateException.class, () -> new SignInSettings("ldap", ""));
    }
}
