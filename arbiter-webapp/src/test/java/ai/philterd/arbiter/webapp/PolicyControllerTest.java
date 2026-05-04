/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Policy;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.GeneralSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

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

class PolicyControllerTest {

    private PolicyRepository policyRepository;
    private PhilterInstanceRepository philterInstanceRepository;
    private BatchRepository batchRepository;
    private AuditLogService auditLogService;
    private GeneralSettingsService generalSettingsService;
    private PolicyController controller;

    @BeforeEach
    void setUp() {
        policyRepository = mock(PolicyRepository.class);
        philterInstanceRepository = mock(PhilterInstanceRepository.class);
        batchRepository = mock(BatchRepository.class);
        auditLogService = mock(AuditLogService.class);
        generalSettingsService = mock(GeneralSettingsService.class);
        controller = new PolicyController(policyRepository, philterInstanceRepository,
                batchRepository, auditLogService, new ObjectMapper(), generalSettingsService);
    }

    private RedirectAttributes flash() {
        return new RedirectAttributesModelMap();
    }

    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error");
        return e == null ? null : e.toString();
    }

    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success");
        return s == null ? null : s.toString();
    }

    // ---------- create ----------

    @Test
    void createRejectsBlankName() {
        final RedirectAttributes ra = flash();
        controller.create("   ", "{}", ra);
        assertEquals("Policy name is required.", error(ra));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankContent() {
        final RedirectAttributes ra = flash();
        controller.create("p", "  ", ra);
        assertEquals("Policy JSON is required.", error(ra));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createRejectsInvalidJson() {
        final RedirectAttributes ra = flash();
        controller.create("p", "{not json", ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).startsWith("Policy is not valid JSON:"));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createRejectsCaseInsensitiveDuplicate() {
        final Policy existing = new Policy();
        existing.setId("e");
        existing.setName("Default");
        when(policyRepository.findFirstByNameIgnoreCase("default")).thenReturn(Optional.of(existing));

        final RedirectAttributes ra = flash();
        controller.create("default", "{}", ra);
        assertEquals("A policy named \"Default\" already exists.", error(ra));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createPersistsValidPolicy() {
        when(policyRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        final String view = controller.create("MyPolicy", "{\"identifiers\":{}}", ra);
        assertEquals("redirect:/policies", view);
        assertNull(error(ra));
        assertEquals("Policy \"MyPolicy\" added.", success(ra));
        verify(policyRepository).save(any(Policy.class));
    }

    // ---------- edit ----------

    @Test
    void editRejectsMissingId() {
        when(policyRepository.findById("ghost")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.edit("ghost", "{}", ra);
        assertEquals("Policy not found.", error(ra));
    }

    @Test
    void editRejectsBlankContent() {
        final Policy p = new Policy();
        p.setId("id-1");
        p.setName("Default");
        p.setContent("{}");
        when(policyRepository.findById("id-1")).thenReturn(Optional.of(p));

        final RedirectAttributes ra = flash();
        controller.edit("id-1", "  ", ra);
        assertEquals("Policy JSON is required.", error(ra));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void editRejectsInvalidJson() {
        final Policy p = new Policy();
        p.setId("id-1");
        p.setName("Default");
        p.setContent("{}");
        when(policyRepository.findById("id-1")).thenReturn(Optional.of(p));

        final RedirectAttributes ra = flash();
        controller.edit("id-1", "not-json", ra);
        assertTrue(error(ra).startsWith("Policy is not valid JSON:"));
        verify(policyRepository, never()).save(any());
    }

    @Test
    void editPersistsValidContent() {
        final Policy p = new Policy();
        p.setId("id-1");
        p.setName("Default");
        p.setContent("{}");
        when(policyRepository.findById("id-1")).thenReturn(Optional.of(p));

        final RedirectAttributes ra = flash();
        final String view = controller.edit("id-1", "{\"identifiers\":{}}", ra);
        assertEquals("redirect:/policies", view);
        assertEquals("Policy \"Default\" updated.", success(ra));
        verify(policyRepository).save(p);
        // Name must remain unchanged.
        assertEquals("Default", p.getName());
    }

    // ---------- delete ----------

    @Test
    void deleteRejectsMissingPolicy() {
        when(policyRepository.findById("ghost")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.delete("ghost", ra);
        assertEquals("Policy not found.", error(ra));
    }

    @Test
    void deleteBlockedWhenInUseByBatch() {
        final Policy p = new Policy();
        p.setId("id-1");
        p.setName("Default");
        when(policyRepository.findById("id-1")).thenReturn(Optional.of(p));

        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample files");
        when(batchRepository.findByPhilterInstanceIdIsNullAndPolicyName("Default"))
                .thenReturn(List.of(b));

        final RedirectAttributes ra = flash();
        controller.delete("id-1", ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("Sample files"),
                "error should name the offending batch: " + error(ra));
        verify(policyRepository, never()).deleteById(anyString());
    }

    @Test
    void deletePersistsWhenNotInUse() {
        final Policy p = new Policy();
        p.setId("id-1");
        p.setName("Unused");
        when(policyRepository.findById("id-1")).thenReturn(Optional.of(p));
        when(batchRepository.findByPhilterInstanceIdIsNullAndPolicyName("Unused"))
                .thenReturn(List.of());

        final RedirectAttributes ra = flash();
        final String view = controller.delete("id-1", ra);
        assertEquals("redirect:/policies", view);
        assertEquals("Policy \"Unused\" removed.", success(ra));
        verify(policyRepository).deleteById("id-1");
    }
}
