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

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.DataImportLogService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.webapp.controllers.BackgroundJobsController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Background Jobs list shows documents an export left out (#40). */
@WebMvcTest(controllers = BackgroundJobsController.class)
@Import(SecurityConfig.class)
class BackgroundJobsPageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockBean private UserGroupsService userGroupsService;
    @MockBean private BatchAccessService batchAccessService;
    @MockBean private DataImportLogService dataImportLogService;

    @Test
    void anExportsSkippedDocumentsAreShownInItsProgress() throws Exception {
        final BackgroundJob job = new BackgroundJob();
        job.setId("job");
        job.setType(BackgroundJob.TYPE_BATCH_EXPORT);
        job.setBatchId("b1");
        job.setBatchName("Claims");
        job.setStatus(BackgroundJob.STATUS_COMPLETED);
        job.setDataFormat("JSONL");
        job.setTotalDocuments(4);
        job.setProcessedDocuments(3);
        job.setSkippedDocuments(1);
        job.setCreatedAt(Instant.now());
        when(backgroundJobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(job));

        final String html = mockMvc.perform(get("/jobs").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("3 of 4 (1 skipped)"), html);
    }
}
