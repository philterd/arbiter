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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.repository.GeneralSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneralSettingsServiceTest {

    private GeneralSettingsRepository repository;
    private GeneralSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(GeneralSettingsRepository.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        service = new GeneralSettingsService(repository, 9090);
    }

    @Test
    void loadReturnsDefaultsWhenNothingSaved() {
        final GeneralSettings settings = service.load();
        assertNotNull(settings);
        assertNotNull(settings.getArbiterUrl());
        assertTrue(settings.getArbiterUrl().startsWith("http://"),
                "default URL should include scheme: " + settings.getArbiterUrl());
        assertTrue(settings.getArbiterUrl().endsWith(":9090"),
                "default URL should include configured port: " + settings.getArbiterUrl());
        assertEquals("UTC", settings.getTimezone());
    }

    @Test
    void loadFillsInDefaultsForBlankFields() {
        final GeneralSettings stored = new GeneralSettings();
        stored.setArbiterUrl("");
        stored.setTimezone(null);
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        final GeneralSettings out = service.load();
        assertTrue(out.getArbiterUrl().endsWith(":9090"));
        assertEquals("UTC", out.getTimezone());
    }

    @Test
    void loadKeepsStoredValues() {
        final GeneralSettings stored = new GeneralSettings();
        stored.setArbiterUrl("https://arbiter.example.com");
        stored.setTimezone("America/New_York");
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        final GeneralSettings out = service.load();
        assertEquals("https://arbiter.example.com", out.getArbiterUrl());
        assertEquals("America/New_York", out.getTimezone());
    }

    @Test
    void loadAppliesDefaultMaxConcurrentDataImportsForLegacyRows() {
        // Pre-existing rows persist the field as 0. load() must clamp to the default
        // so the dispatcher never sees a zero — that would freeze the queue.
        final GeneralSettings stored = new GeneralSettings();
        stored.setMaxConcurrentDataImports(0);
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        assertEquals(GeneralSettingsService.DEFAULT_MAX_CONCURRENT_DATA_IMPORTS,
                service.load().getMaxConcurrentDataImports());
    }

    @Test
    void loadClampsOutOfRangeMaxConcurrentDataImports() {
        // Negative or above-max persisted values fall back to the default.
        final GeneralSettings stored = new GeneralSettings();
        stored.setMaxConcurrentDataImports(99);
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        assertEquals(GeneralSettingsService.DEFAULT_MAX_CONCURRENT_DATA_IMPORTS,
                service.load().getMaxConcurrentDataImports());
    }

    @Test
    void loadKeepsInRangeMaxConcurrentDataImports() {
        final GeneralSettings stored = new GeneralSettings();
        stored.setMaxConcurrentDataImports(5);
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        assertEquals(5, service.load().getMaxConcurrentDataImports());
    }
}
