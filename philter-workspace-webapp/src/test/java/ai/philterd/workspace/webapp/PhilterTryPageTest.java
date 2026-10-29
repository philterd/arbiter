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

import ai.philterd.philter.model.Model;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.controllers.PhilterTryController;
import ai.philterd.workspace.webapp.security.PhilterTryService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trying a policy on text or a PDF, and the Models tab. */
@WebMvcTest(controllers = PhilterTryController.class)
@Import(SecurityConfig.class)
class PhilterTryPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterTryService philter;
    @MockBean private AuditLogService auditLogService;

    @BeforeEach
    void setUp() throws Exception {
        when(philter.policyNames(KEY)).thenReturn(List.of("claims", "default"));
    }

    @Test
    void everyoneCanOpenThePageAndChooseOneOfTheirPolicies() throws Exception {
        final String html = mockMvc.perform(get("/try").with(signedIn("jdoe", "USER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("<option value=\"claims\">") && html.contains("value=\"default\""), html);
        assertTrue(html.contains("Nothing you try here is kept"));
        assertTrue(html.contains("aria-current=\"page\"") && html.contains("Try a Policy"), "its sidebar link");
    }

    @Test
    void textIsRedactedAndShownWithoutBeingKept() throws Exception {
        when(philter.redactText(KEY, "claims", "Write to jdoe@example.com.")).thenReturn("Write to {{{REDACTED-email-address}}}.");

        final String html = mockMvc.perform(post("/try/text").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("policy", " claims ").param("text", "Write to jdoe@example.com."))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("{{{REDACTED-email-address}}}"), html);
        assertTrue(html.contains("value=\"claims\""), "the policy stays chosen");
        verify(auditLogService).log(eq("POLICY_TRY"), eq("Policy"), isNull(),
                eq(Map.of("policy", "claims", "kind", "text", "characters", 26)));
        verifyNoMoreInteractions(auditLogService);
    }

    @Test
    void auditorsCanTryAPolicyToo() throws Exception {
        when(philter.redactText(KEY, "claims", "x")).thenReturn("x");
        mockMvc.perform(post("/try/text").with(signedIn("audit", "AUDITOR")).with(csrf())
                .param("policy", "claims").param("text", "x")).andExpect(status().isOk());
    }

    @Test
    void aPdfIsRedactedAndDownloaded() throws Exception {
        final byte[] redacted = "%PDF-1.7 redacted".getBytes(StandardCharsets.US_ASCII);
        when(philter.redactPdf(KEY, "claims", PDF)).thenReturn(redacted);

        final MvcResult result = mockMvc.perform(multipart("/try/pdf")
                        .file(new MockMultipartFile("file", "Claim for J. Doe.pdf", "application/pdf", PDF))
                        .param("policy", "claims").with(signedIn("jdoe", "USER")).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertArrayEquals(redacted, result.getResponse().getContentAsByteArray());
        assertEquals("attachment; filename=\"Claim_for_J._Doe-redacted.pdf\"",
                result.getResponse().getHeader("Content-Disposition"));
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        verify(auditLogService).log(eq("POLICY_TRY"), eq("Policy"), isNull(),
                eq(Map.of("policy", "claims", "kind", "pdf", "bytes", PDF.length)));
    }

    @Test
    void textPastTheFormLimitIsExplained() throws Exception {
        final String html = mockMvc.perform(post("/try/text").with(signedIn("jdoe", "USER")).with(csrf())
                        .content(new byte[3 * 1024 * 1024]))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("larger than a form can send"), html);
        verify(philter, never()).redactText(any(), any(), any());
    }

    @Test
    void aFileThatIsNotAPdfIsNotSent() throws Exception {
        final String html = mockMvc.perform(multipart("/try/pdf")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                        .param("policy", "claims").with(signedIn("jdoe", "USER")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("That file is not a PDF."), html);
        verify(philter, never()).redactPdf(any(), any(), any());
    }

    @Test
    void philtersRefusalsAreExplained() throws Exception {
        when(philter.redactText(KEY, "nope", "x")).thenThrow(new ClientException("Not Found", 404, "The policy 'nope' does not exist."));
        final String missing = mockMvc.perform(post("/try/text").with(signedIn("jdoe", "USER")).with(csrf())
                .param("policy", "nope").param("text", "x")).andReturn().getResponse().getContentAsString();
        assertTrue(missing.contains("Philter has no policy of yours with that name."), missing);
        assertFalse(missing.contains("does not exist"), "Philter's message is not shown");

        when(philter.redactText(KEY, "broken", "x")).thenThrow(new ClientException("Bad Request", 400,
                "Policy references unavailable custom lists: codenames."));
        assertTrue(mockMvc.perform(post("/try/text").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("policy", "broken").param("text", "x")).andReturn().getResponse().getContentAsString()
                .contains("refers to a custom list you do not have"));
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void theModelsTabListsPhiltersModels() throws Exception {
        final Model model = new Gson().fromJson("{'name':'pheye-person','encoder':'deberta','maxLength':384,"
                + "'maxWidth':12,'sizeBytes':2621440}", Model.class);
        when(philter.models(KEY)).thenReturn(List.of(model));

        final String html = mockMvc.perform(get("/policies/models").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains(">pheye-person<") && html.contains(">2.5 MB<") && html.contains(">384<")
                && html.contains(">12<") && html.contains(">deberta<"), html);
        assertTrue(html.contains("aria-current=\"page\"") && html.contains("Models"), "its Policies tab");
    }

    @Test
    void downloadNamesAreSafe() {
        assertEquals("document-redacted.pdf", PhilterTryController.downloadName("../../.pdf"));
        assertEquals("a_b-redacted.pdf", PhilterTryController.downloadName("a\"b.PDF"));
        assertEquals("document-redacted.pdf", PhilterTryController.downloadName(null));
    }
}
