/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.BatchDefaults;
import ai.philterd.workspace.service.BatchExportService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.webapp.controllers.BatchController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The batch forms are filled from the configured defaults and show the minimum audit sampling rate (#37). */
@WebMvcTest(controllers = BatchController.class)
@Import({SecurityConfig.class, BatchAccessService.class, BatchDefaults.class})
@TestPropertySource(properties = {
        "philter-workspace.batch.defaults.pii-threshold=0.9",
        "philter-workspace.batch.defaults.document-threshold=0.3",
        "philter-workspace.batch.minimum-audit-sampling-rate=1.0"})
class BatchesPageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockBean private UserGroupsService userGroupsService;
    @MockBean private AuditLogService auditLogService;
    @MockBean private BatchExportService batchExportService;

    @Test
    void theFormsArePrefilledFromTheDefaultsAndStateTheMinimum() throws Exception {
        final Group group = new Group();
        group.setId("g1");
        group.setName("Claims team");
        when(groupRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(group)));
        when(batchRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.<Batch>of()));
        when(finalizationPolicyRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(complianceProfileRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(localDirectoryDestinationRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(s3DestinationRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        final String html = mockMvc.perform(get("/batches").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");

        assertTrue(html.contains("id=\"newBatchPiiThreshold\"") && html.contains("value=\"0.9\""), html);
        assertTrue(html.contains("value=\"0.3\""), "the Document Threshold default");
        // With only a minimum set, new batches start at it.
        assertTrue(html.contains("name=\"auditSamplingRate\" step=\"0.01\" max=\"1\" min=\"1.0\" value=\"1.0\""), html);
        assertTrue(html.contains("At least 1.0 in this deployment."), html);
        assertTrue(html.contains("id=\"thresholdsAudit\" type=\"number\" name=\"auditSamplingRate\" step=\"0.01\" max=\"1\" "
                + "required min=\"1.0\""), "the batch settings form");
    }
}
