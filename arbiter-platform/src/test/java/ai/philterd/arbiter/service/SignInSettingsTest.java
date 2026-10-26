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

    @Test
    void beanConditionsReadTheModeAsTheSettingsDo() {
        assertEquals("local", SignInModeCondition.normalize(null));
        assertEquals("local", SignInModeCondition.normalize("  "));
        assertEquals("philter", SignInModeCondition.normalize(" Philter "));
    }

    @Test
    void conditionsMatchTheModeInTheEnvironment() {
        assertTrue(matches(new SignInModeCondition.Local(), null));
        assertTrue(matches(new SignInModeCondition.Local(), ""));
        assertFalse(matches(new SignInModeCondition.Philter(), ""));
        assertTrue(matches(new SignInModeCondition.Philter(), " PHILTER "));
        assertFalse(matches(new SignInModeCondition.Local(), "philter"));
    }

    private static boolean matches(final SignInModeCondition condition, final String mode) {
        final org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        if (mode != null) {
            environment.setProperty("arbiter.sign-in.mode", mode);
        }
        final org.springframework.context.annotation.ConditionContext context =
                org.mockito.Mockito.mock(org.springframework.context.annotation.ConditionContext.class);
        org.mockito.Mockito.when(context.getEnvironment()).thenReturn(environment);
        return condition.matches(context, null);
    }
}
