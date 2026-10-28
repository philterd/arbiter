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

import ai.philterd.philter.model.StatusResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhilterVersionsTest {

    @Test
    void supportsPhilter4() {
        // A deliberate change: update the docs' list of supported Philter versions with it.
        assertEquals(4, PhilterVersions.SUPPORTED_MAJOR);
    }

    @Test
    void anyPhilter4ReleaseIsSupported() {
        assertNull(PhilterVersions.unsupported("4.0.0"));
        assertNull(PhilterVersions.unsupported("4.12.3-SNAPSHOT"));
    }

    @Test
    void otherMajorVersionsAreRefusedNamingTheSupportedOne() {
        final String older = PhilterVersions.unsupported("3.2.0");
        assertTrue(older.contains("version 3.2.0") && older.contains("supports Philter 4.x"), older);
        final String newer = PhilterVersions.unsupported("5.0.0");
        assertTrue(newer.contains("version 5.0.0") && newer.contains("supports Philter 4.x"), newer);
    }

    @Test
    void anUnreadableVersionDoesNotBlockSignIn() {
        assertNull(PhilterVersions.unsupported((String) null));
        assertNull(PhilterVersions.unsupported("unknown"));
    }

    @Test
    void readsTheVersionFromPhiltersHealth() throws Exception {
        final PhilterSignInService service = mock(PhilterSignInService.class);
        final StatusResponse health = new StatusResponse();
        health.setApplicationVersion("5.1.0");
        when(service.health()).thenReturn(health);
        assertTrue(PhilterVersions.unsupported(service).contains("version 5.1.0"));
    }

    @Test
    void aPhilterWithoutHealthIsOlderThan4AndAnUnreachableOneIsNotBlocked() throws Exception {
        final PhilterSignInService philter3 = mock(PhilterSignInService.class);
        when(philter3.health()).thenThrow(new ClientException("Not Found", 404, null));
        assertEquals(PhilterVersions.OLDER_THAN_4, PhilterVersions.unsupported(philter3));

        final PhilterSignInService down = mock(PhilterSignInService.class);
        when(down.health()).thenThrow(new IOException("connection refused"));
        assertNull(PhilterVersions.unsupported(down));
    }
}
