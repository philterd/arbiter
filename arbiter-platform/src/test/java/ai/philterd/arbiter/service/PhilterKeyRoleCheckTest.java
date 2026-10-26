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

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterKeyRoleCheckTest {

    private static PhilterInstance instance(final String name) {
        final PhilterInstance instance = new PhilterInstance();
        instance.setName(name);
        return instance;
    }

    @Test
    void flagsAdministratorsKeysAndLeavesUncheckableOnesAlone() throws Exception {
        final PhilterInstance admin = instance("admin-key");
        final PhilterInstance user = instance("user-key");
        final PhilterInstance down = instance("unreachable");
        final PhilterInstance none = instance("no-key");
        final PhilterInstanceRepository repository = mock(PhilterInstanceRepository.class);
        when(repository.findAll()).thenReturn(List.of(admin, user, down, none));
        final PhilterInstanceClients clients = mock(PhilterInstanceClients.class);
        for (final PhilterInstance i : List.of(admin, user, down)) {
            when(clients.hasApiKey(i)).thenReturn(true);
        }
        when(clients.keyBelongsToAdministrator(admin)).thenReturn(true);
        when(clients.keyBelongsToAdministrator(user)).thenReturn(false);
        when(clients.keyBelongsToAdministrator(down)).thenThrow(new IOException("connection refused"));

        new PhilterKeyRoleCheck(repository, clients).checkAll();

        assertEquals(Boolean.TRUE, admin.getApiKeyAdmin());
        assertEquals(Boolean.FALSE, user.getApiKeyAdmin());
        assertNull(down.getApiKeyAdmin());
        verify(repository).save(admin);
        verify(repository).save(user);
        verify(repository, never()).save(down);
        verify(clients, never()).keyBelongsToAdministrator(none);
    }
}
