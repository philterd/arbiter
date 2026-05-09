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
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.repository.FinalizationPolicyRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.AuditLogService;
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

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchControllerTest {

    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private GroupRepository groupRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private WeightSetRepository weightSetRepository;
    private PhilterInstanceRepository philterInstanceRepository;
    private PhilterDefaultsService philterDefaultsService;
    private FinalizationPolicyRepository finalizationPolicyRepository;
    private ComplianceProfileRepository complianceProfileRepository;
    private BatchController controller;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        groupRepository = mock(GroupRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        weightSetRepository = mock(WeightSetRepository.class);
        philterInstanceRepository = mock(PhilterInstanceRepository.class);
        philterDefaultsService = mock(PhilterDefaultsService.class);
        finalizationPolicyRepository = mock(FinalizationPolicyRepository.class);
        complianceProfileRepository = mock(ComplianceProfileRepository.class);
        // The "fp1" finalization policy id and "cp1" compliance profile id are used by the happy-path test below.
        when(finalizationPolicyRepository.existsById("fp1")).thenReturn(true);
        when(complianceProfileRepository.existsById("cp1")).thenReturn(true);
        controller = new BatchController(batchRepository, documentRepository, groupRepository,
                userGroupsService,
                new ai.philterd.arbiter.service.BatchAccessService(batchRepository, userGroupsService),
                auditLogService, weightSetRepository,
                philterInstanceRepository, philterDefaultsService, finalizationPolicyRepository,
                complianceProfileRepository);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken("user@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ---------- create ----------

    @Test
    void createRequiresAdmin() {
        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, "g", null, null, "Financial", "", null, null, null, user(), ra);
        assertEquals("Only administrators can create batches.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        final RedirectAttributes ra = flash();
        controller.create(" ", null, null, null, "g", null, null, "Financial", "", null, null, null, admin(), ra);
        assertEquals("Batch name is required.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsThresholdOutOfRange() {
        final RedirectAttributes ra = flash();
        controller.create("b", null, 1.5, null, "g", null, null, "Financial", "", null, null, null, admin(), ra);
        assertEquals("PII threshold must be between 0 and 1.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingGroup() {
        final RedirectAttributes ra = flash();
        when(groupRepository.existsById("missing")).thenReturn(false);
        controller.create("b", null, null, null, "missing", null, null, "Financial", "", null, null, null, admin(), ra);
        assertEquals("A valid group must be selected.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownPhilterInstance() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(philterInstanceRepository.existsById("ghost")).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, "g1", "ghost", null, "Financial", "", null, null, null, admin(), ra);
        assertEquals("Selected Philter instance no longer exists.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, "g1", "", null, "  ", "", null, null, null, admin(), ra);
        assertEquals("Domain is required.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, "g1", "", null, "Aerospace", "", null, null, null, admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid choice"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateBatchName() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        final Batch existing = new Batch();
        existing.setId("b-1");
        existing.setName("Sample");
        when(batchRepository.findByName("Sample")).thenReturn(Optional.of(existing));

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, "g1", "", null, "Financial", "", null, null, null, admin(), ra);
        assertEquals("A batch named \"Sample\" already exists.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createSucceedsWithEmbeddedPhilterAndValidDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(batchRepository.findByName("Sample")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        final String view = controller.create("Sample", null, 0.5, 0.2, "g1", "", null, "Healthcare", "", "fp1", "cp1", null, admin(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        assertEquals("Batch \"Sample\" created.", success(ra));
        verify(batchRepository).save(any(Batch.class));
    }

    // ---------- changePhilter ----------

    @Test
    void changePhilterRequiresAdmin() {
        final RedirectAttributes ra = flash();
        controller.changePhilter("b1", "p", null, user(), ra);
        assertEquals("Only administrators can modify batches.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changePhilterMissingBatch() {
        when(batchRepository.findById("b1")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.changePhilter("b1", "", "policy", admin(), ra);
        assertEquals("Batch not found.", error(ra));
    }

    @Test
    void changePhilterRejectsUnknownInstance() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(philterInstanceRepository.existsById("ghost")).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.changePhilter("b1", "ghost", null, admin(), ra);
        assertEquals("Selected Philter instance no longer exists.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changePhilterAllowsEmbeddedNoPolicy() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final RedirectAttributes ra = flash();
        final String view = controller.changePhilter("b1", "", "", admin(), ra);
        assertEquals("redirect:/batches", view);
        assertTrue(success(ra).contains("Embedded Philter"));
        assertTrue(success(ra).contains("no policy"));
        verify(batchRepository).save(b);
        // Both fields must be cleared to null in the saved batch.
        assertNull(b.getPhilterInstanceId());
        assertNull(b.getPolicyName());
    }

    // ---------- changeGroup ----------

    @Test
    void changeGroupRequiresAdmin() {
        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "g1", user(), ra);
        assertEquals("Only administrators can modify batches.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changeGroupRejectsInvalidGroup() {
        final Batch b = new Batch();
        b.setId("b1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(groupRepository.existsById(anyString())).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "ghost", admin(), ra);
        assertEquals("A valid group must be selected.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    // ---------- close ----------

    @Test
    void closeRequiresAdmin() {
        final RedirectAttributes ra = flash();
        controller.close("b1", user(), ra);
        assertEquals("Only administrators can close batches.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeRejectsMissingBatch() {
        when(batchRepository.findById("b1")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertEquals("Batch not found.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeRejectsAlreadyClosed() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        b.setClosed(true);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertEquals("Batch is already closed.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeRejectsWhenDocumentsAreNotAllRejectedOrFinalized() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(5L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(3L);

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("cannot be closed"));
        assertTrue(error(ra).contains("2 documents are"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeUsesSingularWhenOneDocumentRemains() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(2L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(1L);

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("1 document is"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeSucceedsWhenAllDocumentsAreRejectedOrFinalized() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(4L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(4L);

        final RedirectAttributes ra = flash();
        final String view = controller.close("b1", admin(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        assertEquals("Batch \"Sample\" closed.", success(ra));
        assertTrue(b.isClosed());
        assertNotNull(b.getClosedAt());
        verify(batchRepository).save(b);
    }

    @Test
    void closeSucceedsForEmptyBatch() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Empty");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(0L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(0L);

        final RedirectAttributes ra = flash();
        final String view = controller.close("b1", admin(), ra);
        assertEquals("redirect:/batches", view);
        assertEquals("Batch \"Empty\" closed.", success(ra));
        verify(batchRepository).save(b);
    }
}
