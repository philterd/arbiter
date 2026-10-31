/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.GeneralSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminGeneralControllerTest {

    private ai.philterd.workspace.service.DocumentSearchService searchService;
    private ai.philterd.workspace.service.SearchIndexRebuildService rebuildService;

    private GeneralSettingsService settingsService;
    private AuditLogService auditLogService;
    private ai.philterd.workspace.service.FullTextSearchIndexManager indexManager;
    private AdminGeneralController controller;

    @BeforeEach
    void setUp() {
        settingsService = mock(GeneralSettingsService.class);
        when(settingsService.load()).thenReturn(new GeneralSettings());
        auditLogService = mock(AuditLogService.class);
        indexManager = mock(ai.philterd.workspace.service.FullTextSearchIndexManager.class);
        searchService = mock(ai.philterd.workspace.service.DocumentSearchService.class);
        // These tests cover the OpenSearch form; the embedded backend's are below.
        when(searchService.backend()).thenReturn("opensearch");
        rebuildService = mock(ai.philterd.workspace.service.SearchIndexRebuildService.class);
        controller = new AdminGeneralController(settingsService, auditLogService, indexManager, searchService,
                rebuildService);
    }

    private RedirectAttributes flash() { return new RedirectAttributesModelMap(); }

    /**
     * Configure {@code settingsService.load()} to return the supplied entity so the
     * controller's previous-value reads pick up the test's pre-state.
     */
    private void givenSettings(final GeneralSettings settings) {
        when(settingsService.load()).thenReturn(settings);
    }

    private static ai.philterd.workspace.service.FullTextSearchIndexManager.Result probe(
            final ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome outcome,
            final String message,
            final String existing,
            final String expected) {
        return new ai.philterd.workspace.service.FullTextSearchIndexManager.Result(
                outcome, message, existing, expected);
    }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ---------- the embedded search backend (#7) ----------

    @Test
    void withTheEmbeddedBackendOnlyTheSwitchIsSavedAndTheIndexIsOpened() {
        when(searchService.backend()).thenReturn("embedded");
        when(searchService.probe()).thenReturn(ai.philterd.workspace.service.DocumentSearchService.State.REACHABLE);
        final GeneralSettings settings = new GeneralSettings();
        settings.setOpensearchEndpoint("http://kept:9200");
        givenSettings(settings);

        final RedirectAttributes on = flash();
        controller.saveFullTextSearch(true, null, null, null, null, null, on);

        assertTrue(settings.isFullTextSearchEnabled());
        assertEquals("http://kept:9200", settings.getOpensearchEndpoint(), "OpenSearch settings are left alone");
        assertTrue(success(on).startsWith("Full text search enabled."), success(on));
        verify(indexManager, never()).ensureIndex(any(), any(), any(), any());
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("GENERAL_SETTINGS_CHANGE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("previousFullTextSearchEnabled", false,
                        "fullTextSearchEnabled", true, "searchBackend", "embedded")));

        when(searchService.probe()).thenReturn(ai.philterd.workspace.service.DocumentSearchService.State.UNREACHABLE);
        final RedirectAttributes broken = flash();
        controller.saveFullTextSearch(true, null, null, null, null, null, broken);
        assertTrue(error(broken).contains("could not be opened"), error(broken));
    }

    @Test
    void aRebuildIsStartedOrItsRefusalShown() {
        when(rebuildService.start("admin@example.com")).thenReturn(null).thenReturn("A rebuild is already running.");
        final org.springframework.security.core.Authentication admin =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin@example.com", null, java.util.List.of());
        final RedirectAttributes started = flash();
        controller.rebuildSearchIndex(admin, started);
        assertTrue(success(started).startsWith("Rebuilding the full-text index."));
        final RedirectAttributes again = flash();
        controller.rebuildSearchIndex(admin, again);
        assertEquals("A rebuild is already running.", error(again));
    }

    // ---------- case types (#16) ----------

    @Test
    void aCaseTypeIsMappedNormalizedReplacedAndRemovedWithAudits() {
        final GeneralSettings settings = new GeneralSettings();
        givenSettings(settings);

        final RedirectAttributes added = flash();
        controller.saveCaseTypePolicy(" Civil ", " civil-filings ", added);
        assertEquals("Case type \"civil\" now uses policy \"civil-filings\".", success(added));
        assertEquals("civil-filings", settings.policyForCaseType("civil"));

        controller.saveCaseTypePolicy("civil", "civil-2", flash());
        assertEquals(1, settings.getCaseTypePolicies().size(), "mapping again replaces");
        assertEquals("civil-2", settings.policyForCaseType("civil"));
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("CASE_TYPE_POLICY_CHANGE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("caseType", "civil",
                        "previousPolicyName", "civil-filings", "policyName", "civil-2")));

        final RedirectAttributes removed = flash();
        controller.removeCaseTypePolicy("CIVIL", removed);
        assertTrue(settings.getCaseTypePolicies().isEmpty());
        assertEquals("Case type \"civil\" is no longer mapped.", success(removed));
        final RedirectAttributes again = flash();
        controller.removeCaseTypePolicy("civil", again);
        assertEquals("That case type is not mapped.", error(again));
    }

    @Test
    void anInvalidCaseTypeOrMissingPolicyIsRefused() {
        givenSettings(new GeneralSettings());
        final RedirectAttributes bad = flash();
        controller.saveCaseTypePolicy("civil/appeal", "p", bad);
        assertTrue(error(bad).startsWith("The case type must be"), error(bad));
        final RedirectAttributes blank = flash();
        controller.saveCaseTypePolicy("civil", "  ", blank);
        assertEquals("A case type and a policy are both required.", error(blank));
        verify(settingsService, never()).save(any());
    }

    // ---------- detection rules (#39) ----------

    @Test
    void detectionRuleSettingsAreSavedAndAudited() {
        final GeneralSettings settings = new GeneralSettings();
        givenSettings(settings);
        assertFalse(settings.isReviewersConfirmRules(), "administrators only by default");
        assertEquals(5, settings.getRuleProposalRefusals());

        final RedirectAttributes ra = flash();
        controller.saveDetectionRules(true, 0, ra);

        assertEquals("Detection rule settings saved.", success(ra));
        assertTrue(settings.isReviewersConfirmRules());
        assertEquals(0, settings.getRuleProposalRefusals());
        verify(settingsService).save(settings);
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("GENERAL_SETTINGS_CHANGE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("previousReviewersConfirmRules", false,
                        "reviewersConfirmRules", true, "previousRuleProposalRefusals", 5,
                        "ruleProposalRefusals", 0)));
    }

    @Test
    void detectionRuleRefusalsOutOfRangeAreRefused() {
        givenSettings(new GeneralSettings());
        final RedirectAttributes negative = flash();
        controller.saveDetectionRules(false, -1, negative);
        assertEquals("Refusals before a proposal must be between 0 and 1000.", error(negative));
        controller.saveDetectionRules(false, 1001, flash());
        verify(settingsService, never()).save(any());
    }

    // ---------- saveUrl ----------

    @Test
    void urlBlankRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("   ", ra);
        assertEquals("Philter Workspace URL is required.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlMissingSchemeRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("philter-workspace.local:8080", ra);
        assertEquals("Philter Workspace URL must start with http:// or https://.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlInvalidUriRejected() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("http://bad uri with spaces", ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).startsWith("Philter Workspace URL is not a valid URI:"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void urlTrailingSlashStripped() {
        final RedirectAttributes ra = flash();
        controller.saveUrl("https://philter-workspace.example.com/", ra);
        assertNull(error(ra));
        // load() returns a fresh GeneralSettings; verify save was called with the trimmed URL.
        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        assertEquals("https://philter-workspace.example.com", captor.getValue().getWorkspaceUrl());
        assertEquals("Philter Workspace URL saved.", success(ra));
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

    // ---------- saveFullTextSearch ----------

    @Test
    void fullTextSearchRejectsBlankEndpoint() {
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "  ", "philter-workspace-documents",
                null, null, null, ra);
        assertEquals("OpenSearch endpoint is required.", error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void fullTextSearchRejectsEndpointWithoutScheme() {
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "localhost:9200", "philter-workspace-documents",
                null, null, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("http://"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void fullTextSearchRejectsBlankIndexName() {
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://localhost:9200", "  ",
                null, null, null, ra);
        assertEquals("Index name is required.", error(ra));
    }

    @Test
    void fullTextSearchRejectsIndexNameWithUppercase() {
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://localhost:9200", "Philter-Workspace-Documents",
                null, null, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("lower-case"));
    }

    @Test
    void fullTextSearchRejectsIndexNameWithSpaces() {
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://localhost:9200", "philter-workspace docs",
                null, null, null, ra);
        assertNotNull(error(ra));
    }

    @Test
    void fullTextSearchSavesAndAuditsOnCreatedOutcome() {
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.CREATED,
                        "Index 'philter-workspace-documents' created with the full-text search mapping.",
                        null, null));

        final RedirectAttributes ra = flash();
        final String view = controller.saveFullTextSearch(true, "http://localhost:9200",
                "philter-workspace-documents", "alice", "secret", null, ra);

        assertEquals("redirect:/admin/general", view);
        assertNotNull(success(ra));
        assertTrue(success(ra).contains("created"));

        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        final GeneralSettings saved = captor.getValue();
        assertTrue(saved.isFullTextSearchEnabled());
        assertEquals("http://localhost:9200", saved.getOpensearchEndpoint());
        assertEquals("philter-workspace-documents", saved.getOpensearchIndexName());
        assertEquals("alice", saved.getOpensearchUsername());
        assertEquals("secret", saved.getOpensearchPassword());
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("GENERAL_SETTINGS_CHANGE"),
                org.mockito.ArgumentMatchers.eq("Settings"), any(), any());
    }

    @Test
    void fullTextSearchSavesOnAlreadyMatchesOutcome() {
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.ALREADY_MATCHES,
                        "Index already matches.", null, null));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://localhost:9200", "philter-workspace-documents",
                null, null, null, ra);
        verify(settingsService).save(any());
    }

    @Test
    void fullTextSearchDoesNotSaveOnMappingMismatchAndStashesDiff() {
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.MAPPING_MISMATCH,
                        "Mismatch detected.",
                        "{\"existing\":\"keyword\"}",
                        "{\"expected\":\"text\"}"));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://localhost:9200", "philter-workspace-documents",
                "alice", "secret", null, ra);

        // Settings must NOT be saved on mismatch — operator confirms first.
        verify(settingsService, never()).save(any());
        // Diff stashed in flash for the modal to render.
        assertEquals(true, ra.getFlashAttributes().get("ftsMismatch"));
        assertEquals("Mismatch detected.", ra.getFlashAttributes().get("ftsMismatchMessage"));
        assertEquals("{\"existing\":\"keyword\"}", ra.getFlashAttributes().get("ftsExistingMapping"));
        assertEquals("{\"expected\":\"text\"}", ra.getFlashAttributes().get("ftsExpectedMapping"));
        // Form values rebound so the modal's hidden form has the same submission.
        assertEquals("http://localhost:9200", ra.getFlashAttributes().get("ftsPendingEndpoint"));
        assertEquals("philter-workspace-documents", ra.getFlashAttributes().get("ftsPendingIndexName"));
        assertEquals("alice", ra.getFlashAttributes().get("ftsPendingUsername"));
        assertEquals(true, ra.getFlashAttributes().get("ftsPendingEnabled"));
    }

    @Test
    void fullTextSearchReportsErrorAndDoesNotSaveOnUnreachableServer() {
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.UNREACHABLE,
                        "Could not reach OpenSearch at http://x: refused", null, null));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://x", "philter-workspace-documents", null, null, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("Could not reach"));
        verify(settingsService, never()).save(any());
    }

    @Test
    void fullTextSearchReportsErrorAndDoesNotSaveOnServerError() {
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.SERVER_ERROR,
                        "OpenSearch returned HTTP 500", null, null));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://x", "philter-workspace-documents", null, null, null, ra);
        assertNotNull(error(ra));
        verify(settingsService, never()).save(any());
    }

    @Test
    void disablingFullTextSearchSkipsTheIndexProbe() {
        // When the operator turns the feature off, the controller should never call
        // ensureIndex — they shouldn't have to wait for a (possibly down) cluster just
        // to disable indexing.
        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(false, "http://localhost:9200", "philter-workspace-documents",
                null, null, null, ra);

        verify(indexManager, never()).ensureIndex(any(), any(), any(), any());
        verify(settingsService).save(any());
        assertNotNull(success(ra));
    }

    @Test
    void blankPasswordKeepsExistingStoredPassword() {
        final GeneralSettings existing = new GeneralSettings();
        existing.setOpensearchPassword("previous-pass");
        givenSettings(existing);
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.ALREADY_MATCHES,
                        "ok", null, null));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://x:9200", "philter-workspace-documents",
                "alice", "  ", null, ra);

        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        assertEquals("previous-pass", captor.getValue().getOpensearchPassword(),
                "Blank password input must keep the previously-stored value, not wipe it.");
    }

    @Test
    void clearPasswordCheckboxResetsStoredPasswordToNull() {
        final GeneralSettings existing = new GeneralSettings();
        existing.setOpensearchPassword("previous-pass");
        givenSettings(existing);
        when(indexManager.ensureIndex(any(), any(), any(), any()))
                .thenReturn(probe(ai.philterd.workspace.service.FullTextSearchIndexManager.Outcome.ALREADY_MATCHES,
                        "ok", null, null));

        final RedirectAttributes ra = flash();
        controller.saveFullTextSearch(true, "http://x:9200", "philter-workspace-documents",
                "alice", "ignored", true, ra);

        final org.mockito.ArgumentCaptor<GeneralSettings> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralSettings.class);
        verify(settingsService).save(captor.capture());
        assertNull(captor.getValue().getOpensearchPassword(),
                "Clear-password checkbox wins over a typed password and resets to null.");
    }

    // ---------- confirmFullTextSearch ----------

    @Test
    void confirmFullTextSearchSavesWithoutProbing() {
        final RedirectAttributes ra = flash();
        controller.confirmFullTextSearch(true, "http://x:9200", "philter-workspace-documents",
                "alice", "secret", null, ra);

        // Crucial: the confirm path must NOT issue another probe — the operator already
        // chose to continue with the existing mismatched mapping.
        verify(indexManager, never()).ensureIndex(any(), any(), any(), any());
        verify(settingsService).save(any());
        assertNotNull(success(ra));
        assertTrue(success(ra).contains("non-canonical"));
    }

    @Test
    void confirmFullTextSearchAlsoValidatesEndpointAndIndexName() {
        final RedirectAttributes ra = flash();
        controller.confirmFullTextSearch(true, "ftp://x", "philter-workspace-documents",
                null, null, null, ra);
        assertNotNull(error(ra),
                "Confirm path must not bypass URL/index validation — otherwise an attacker could "
                        + "skip the prompt and write whatever they want.");
        verify(settingsService, never()).save(any());
    }
}
