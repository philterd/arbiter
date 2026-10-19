/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.PhilterInstanceClients;
import ai.philterd.arbiter.service.SymmetricCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPhilterControllerApiKeyTest {

    private PhilterInstanceRepository repository;
    private SymmetricCipher cipher;
    private AdminPhilterController controller;

    @BeforeEach
    void setUp() {
        repository = mock(PhilterInstanceRepository.class);
        cipher = mock(SymmetricCipher.class);
        when(cipher.encrypt(any())).thenReturn("encrypted");
        when(repository.findByName(any())).thenReturn(Optional.empty());
        controller = new AdminPhilterController(repository, mock(AuditLogService.class),
                mock(PhilterDefaultsService.class), cipher,
                new DataSourceHostAllowList("philter.example.com"), mock(PhilterInstanceClients.class),
                mock(ai.philterd.arbiter.service.PhilterInstanceTls.class));
    }

    private static Object error(final RedirectAttributes ra) {
        return ra.getFlashAttributes().get("error");
    }

    @Test
    void createRejectsMissingApiKey() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.create("prod", "philter.example.com", 8080, null, ra);
        assertEquals("API key is required.", error(ra));

        final RedirectAttributes blank = new RedirectAttributesModelMap();
        controller.create("prod", "philter.example.com", 8080, "   ", blank);
        assertEquals("API key is required.", error(blank));

        verify(repository, never()).save(any());
    }

    @Test
    void createStoresEncryptedApiKey() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.create("prod", "philter.example.com", 8080, " key ", ra);

        assertEquals(null, error(ra));
        verify(cipher).encrypt("key");
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                (PhilterInstance i) -> "encrypted".equals(i.getEncryptedApiKey())));
    }

    @Test
    void setApiKeyRejectsBlankInsteadOfClearing() {
        final PhilterInstance instance = new PhilterInstance();
        instance.setId("p1");
        instance.setName("prod");
        instance.setEncryptedApiKey("existing");
        when(repository.findById("p1")).thenReturn(Optional.of(instance));

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setApiKey("p1", "", ra);

        assertEquals("API key is required.", error(ra));
        assertEquals("existing", instance.getEncryptedApiKey());
        verify(repository, never()).save(any());
    }
}
