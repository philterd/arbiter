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

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterKeyRoleCheckTest {

    @Test
    void asksPhilterForTheKeysRole() throws Exception {
        final PhilterSettings philter = mock(PhilterSettings.class);
        when(philter.checkKeyRole()).thenReturn(true);

        new PhilterKeyRoleCheck(philter).check();

        verify(philter).checkKeyRole();
    }

    @Test
    void anUnreachablePhilterDoesNotStopStartup() throws Exception {
        final PhilterSettings philter = mock(PhilterSettings.class);
        when(philter.checkKeyRole()).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(() -> new PhilterKeyRoleCheck(philter).check());
    }
}
