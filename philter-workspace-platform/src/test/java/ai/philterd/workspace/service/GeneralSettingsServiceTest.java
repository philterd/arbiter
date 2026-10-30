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

import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.repository.GeneralSettingsRepository;
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
    void fullTextSearchIsOffOnANewInstallation() {
        // It indexes original, unredacted text, so an administrator turns it on deliberately.
        org.junit.jupiter.api.Assertions.assertFalse(service.load().isFullTextSearchEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(new GeneralSettings().isFullTextSearchEnabled());

        final GeneralSettings saved = new GeneralSettings();
        saved.setFullTextSearchEnabled(true);
        when(repository.findById(any())).thenReturn(Optional.of(saved));
        assertTrue(service.load().isFullTextSearchEnabled(), "a saved setting is kept");
    }

    @Test
    void loadReturnsDefaultsWhenNothingSaved() {
        final GeneralSettings settings = service.load();
        assertNotNull(settings);
        assertNotNull(settings.getWorkspaceUrl());
        assertTrue(settings.getWorkspaceUrl().startsWith("http://"),
                "default URL should include scheme: " + settings.getWorkspaceUrl());
        assertTrue(settings.getWorkspaceUrl().endsWith(":9090"),
                "default URL should include configured port: " + settings.getWorkspaceUrl());
        assertEquals("UTC", settings.getTimezone());
    }

    @Test
    void loadFillsInDefaultsForBlankFields() {
        final GeneralSettings stored = new GeneralSettings();
        stored.setWorkspaceUrl("");
        stored.setTimezone(null);
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        final GeneralSettings out = service.load();
        assertTrue(out.getWorkspaceUrl().endsWith(":9090"));
        assertEquals("UTC", out.getTimezone());
    }

    @Test
    void loadKeepsStoredValues() {
        final GeneralSettings stored = new GeneralSettings();
        stored.setWorkspaceUrl("https://philter-workspace.example.com");
        stored.setTimezone("America/New_York");
        when(repository.findById(GeneralSettings.SINGLETON_ID)).thenReturn(Optional.of(stored));

        final GeneralSettings out = service.load();
        assertEquals("https://philter-workspace.example.com", out.getWorkspaceUrl());
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
