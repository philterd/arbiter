/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.FinalizationPolicyRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.BatchExportService;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.webapp.controllers.BatchController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the indistinguishability contract for every write endpoint on
 * {@link BatchController}: lookup-miss and access-denied must produce byte-identical
 * response shapes — same redirect target, same flash {@code error} attribute, no
 * mention of the supplied batch id. Before this fix a non-admin/non-lead user could
 * read the differing flash messages ({@code "Batch not found."} vs {@code "Only
 * administrators or the batch's team lead can…"}) and enumerate batch ids across
 * groups they have no membership in.
 *
 * <p>The audit log still distinguishes the two outcomes; this is a user-facing
 * surface only.
 */
class BatchControllerExistenceLeakTest {

    private BatchRepository batchRepository;
    private UserGroupsService userGroupsService;
    private BatchController controller;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        final BatchAccessService batchAccessService =
                new BatchAccessService(batchRepository, userGroupsService);
        controller = new BatchController(
                batchRepository,
                mock(DocumentRepository.class),
                mock(GroupRepository.class),
                userGroupsService,
                batchAccessService,
                mock(AuditLogService.class),
                mock(WeightSetRepository.class),
                mock(PhilterInstanceRepository.class),
                mock(PhilterDefaultsService.class),
                mock(FinalizationPolicyRepository.class),
                mock(ComplianceProfileRepository.class),
                mock(ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository.class),
                mock(ai.philterd.arbiter.repository.S3DestinationRepository.class),
                mock(BatchExportService.class));
    }

    /** A regular reviewer with no lead authority anywhere. */
    private static Authentication nonLead() {
        return new UsernamePasswordAuthenticationToken("alice@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error");
        return e == null ? null : e.toString();
    }

    /** Configure the world so lookup of {@code b-missing} misses; lookup of
     * {@code b-real} returns a batch belonging to a group the caller does not lead. */
    private void setupMissAndDeniedBatches() {
        when(batchRepository.findById("b-missing")).thenReturn(Optional.empty());
        final Batch real = new Batch();
        real.setId("b-real");
        real.setName("Confidential Batch");
        real.setGroupId("g-otherteam");
        when(batchRepository.findById("b-real")).thenReturn(Optional.of(real));
        // alice@x.com leads nothing — every canLeadBatch returns false.
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com")).thenReturn(Set.of());
    }

    /**
     * For each write endpoint: invoke once against a non-existent batch id and once
     * against a real-but-inaccessible batch id, and assert that the two responses are
     * indistinguishable from outside the audit log.
     */
    private void assertMissAndDeniedAreIndistinguishable(
            final java.util.function.Function<String, String> invokeWithFlash,
            final RedirectAttributes missFlash,
            final RedirectAttributes deniedFlash) {
        final String missView = invokeWithFlash.apply("b-missing");
        final String deniedView = invokeWithFlash.apply("b-real");

        assertEquals(missView, deniedView,
                "miss vs denied returned different redirect targets — leaks existence");
        assertNotNull(error(missFlash), "miss path produced no error message");
        assertNotNull(error(deniedFlash), "denied path produced no error message");
        assertEquals(error(missFlash), error(deniedFlash),
                "miss vs denied flash bodies differ — leaks existence");
        assertEquals("Batch not found.", error(missFlash),
                "miss body should be the generic 'Batch not found.'");
        // Defence-in-depth: neither body mentions the supplied id, the batch name,
        // or the word 'lead' / 'administrator' that the old shape leaked.
        assertFalse(error(missFlash).contains("b-real"),
                "miss body leaked the real id: " + error(missFlash));
        assertFalse(error(deniedFlash).contains("b-real"),
                "denied body leaked the real id: " + error(deniedFlash));
        assertFalse(error(deniedFlash).toLowerCase().contains("team lead"),
                "denied body still mentions 'team lead': " + error(deniedFlash));
        assertFalse(error(deniedFlash).contains("Confidential Batch"),
                "denied body leaked the batch name: " + error(deniedFlash));
    }

    // ---------- per-endpoint coverage ----------

    @Test
    void changePhilterTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        assertMissAndDeniedAreIndistinguishable(id -> controller.changePhilter(
                id, "", "", nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    @Test
    void changeDomainTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        assertMissAndDeniedAreIndistinguishable(id -> controller.changeDomain(
                id, "legal", nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    @Test
    void saveWeightsTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        assertMissAndDeniedAreIndistinguishable(id -> controller.saveWeights(
                id, List.of(), List.of(), null, null,
                nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    @Test
    void editThresholdsTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        assertMissAndDeniedAreIndistinguishable(id -> controller.editThresholds(
                id, 0.5, 0.5, null, null, null, null,
                nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    @Test
    void closeTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        assertMissAndDeniedAreIndistinguishable(id -> controller.close(
                id, nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    @Test
    void exportTreatsMissAndDeniedIdentically() {
        setupMissAndDeniedBatches();
        final RedirectAttributes missFlash = flash();
        final RedirectAttributes deniedFlash = flash();
        // The destination arg is intentionally invalid — the existence/access checks
        // run first, so neither path should reach the destination parsing.
        assertMissAndDeniedAreIndistinguishable(id -> controller.export(
                id, "LOCAL:ignored", "JSONL",
                nonLead(), id.equals("b-missing") ? missFlash : deniedFlash),
                missFlash, deniedFlash);
    }

    // ---------- cross-cutting sanity ----------

    @Test
    void grantingLeadershipAdmitsBothPaths() {
        // Sanity: when the user actually leads the batch's group, denied-because-access
        // is no longer the failure mode — changeDomain proceeds past the gate.
        setupMissAndDeniedBatches();
        when(userGroupsService.leadGroupIdsForEmail("alice@x.com"))
                .thenReturn(Set.of("g-otherteam"));
        final RedirectAttributes ra = flash();
        final String view = controller.changeDomain("b-real", "legal", nonLead(), ra);

        assertEquals("redirect:/batches", view);
        // No "Batch not found." flash on a successful run.
        if (error(ra) != null) {
            assertFalse(error(ra).equals("Batch not found."),
                    "successful lead-call should not flash 'Batch not found.'");
        }
    }
}
