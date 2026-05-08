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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminGeneralControllerTest {

    private GeneralSettingsService settingsService;
    private AuditLogService auditLogService;
    private AdminGeneralController controller;

    @BeforeEach
    void setUp() {
        settingsService = mock(GeneralSettingsService.class);
        when(settingsService.load()).thenReturn(new GeneralSettings());
        auditLogService = mock(AuditLogService.class);
        controller = new AdminGeneralController(settingsService, auditLogService);
    }

    private RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ---------- saveUrl ----------

    @Test
    void urlBlankRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("   ", ra);
        assertEquals("Arbiter URL is required.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlMissingSchemeRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("arbiter.local:8080", ra);
        assertEquals("Arbiter URL must start with http:// or https://.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlInvalidUriRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("http://bad uri with spaces", ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).startsWith("Arbiter URL is not a valid URI:"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlTrailingSlashStripped() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("https://arbiter.example.com/", ra);
        assertNull(error(ra));
        // load() returns a fresh GeneralSettings; verify save was called with the trimmed URL.
        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        assertEquals("https://arbiter.example.com", captor.getValue().getArbiterUrl());
        assertEquals("Arbiter URL saved.", success(ra));
    }

    // ---------- saveTimezone ----------

    @Test
    void timezoneBlankRejected() {
        final RedirectAttributes ra = flash();
        controller.saveTimezone(" ", ra);
        assertEquals("Timezone is required.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void timezoneInvalidRejected() {
        final RedirectAttributes ra = flash();
        controller.saveTimezone("Atlantis/Trench", ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid IANA zone"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void timezoneValidPersisted() {
        final RedirectAttributes ra = flash();
        final String view = controller.saveTimezone("America/Chicago", ra);
        assertEquals("redirect:/admin/general", view);
        assertEquals("Timezone saved.", success(ra));
        verify(settingsService).save(any(GeneralSettings.class));
    }

    // ---------- saveMaxConcurrentDataImports ----------

    @Test
    void maxConcurrentDataImportsBelowMinRejected() {
        final RedirectAttributes ra = flash();
        controller.saveMaxConcurrentDataImports(0, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("between 1 and 10"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void maxConcurrentDataImportsAboveMaxRejected() {
        final RedirectAttributes ra = flash();
        controller.saveMaxConcurrentDataImports(11, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("between 1 and 10"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void maxConcurrentDataImportsValidPersisted() {
        final RedirectAttributes ra = flash();
        final String view = controller.saveMaxConcurrentDataImports(5, ra);
        assertEquals("redirect:/admin/general", view);
        assertEquals("Max concurrent data imports saved.", success(ra));

        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        assertEquals(5, captor.getValue().getMaxConcurrentDataImports());
    }
}
