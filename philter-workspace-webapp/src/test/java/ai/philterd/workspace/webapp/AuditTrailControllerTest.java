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

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuditTrailService;
import ai.philterd.workspace.service.DocumentLedgerService;
import ai.philterd.workspace.webapp.controllers.AuditTrailController;
import ai.philterd.workspace.webapp.controllers.AuditTrailWriter;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The audit trail with Philter's ledger (#18): who may read it, what it holds, and that it is audited. */
@WebMvcTest(controllers = AuditTrailController.class)
@Import({SecurityConfig.class, AuditTrailWriter.class})
class AuditTrailControllerTest extends WebMvcSliceMocks {

    private static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DocumentLedgerService ledgerService;
    @MockitoBean private AuditTrailService trailService;
    @MockitoBean private AuditLogService auditLogService;

    private Document document;
    private AuditTrailService.Trail trail;

    @BeforeEach
    void setUp() {
        document = new Document();
        document.setId("d1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document));

        final AuditLog approved = new AuditLog();
        approved.setTimestamp(T0.plusSeconds(60));
        approved.setUserEmail("reviewer@example.com");
        approved.setAction("DOCUMENT_APPROVAL");
        approved.setResourceType("Document");
        approved.setResourceId("d1");
        approved.setOutcome("SUCCESS");
        approved.setDetails(Map.of("approvedBy", "reviewer@example.com"));
        final DocumentLedgerService.Entry entry = new DocumentLedgerService.Entry("p1", T0, "ssn", 14, "h1", null,
                "dh", null, "court", 3, "pch", "k1", true, null);
        final DocumentLedgerService.Chain chain = new DocumentLedgerService.Chain("p1", "OK", true, true, true, 1, 0,
                null, 1, false);
        trail = new AuditTrailService.Trail(List.of(
                new AuditTrailService.Event(T0, AuditTrailService.SOURCE_LEDGER, "d1", null, entry),
                new AuditTrailService.Event(T0.plusSeconds(60), AuditTrailService.SOURCE_WORKSPACE, "d1", approved, null)),
                List.of(new AuditTrailService.DocumentChain("d1", chain)), false, false);
        when(trailService.forDocument(document)).thenReturn(trail);
        when(trailService.forRange(any(), any())).thenReturn(trail);
        when(ledgerService.forDocument(document)).thenReturn(new DocumentLedgerService.Ledger(List.of(chain),
                List.of(entry)));
    }

    @Test
    void onlyAdministratorsAndAuditorsReadIt() throws Exception {
        for (String path : List.of("/api/v1/documents/d1/ledger", "/api/v1/documents/d1/audit-trail",
                "/admin/audit/trail")) {
            mockMvc.perform(get(path).with(signedIn("reviewer", "USER"))).andExpect(status().isForbidden());
        }
        verify(trailService, never()).forDocument(any());
        verify(trailService, never()).forRange(any(), any());
        verify(ledgerService, never()).forDocument(any());
        verify(auditLogService, never()).log(any(), any(), any(), anyMap());
    }

    @Test
    void anAuditorDownloadsADocumentsTrailAsCsvAndTheExportIsRecordedFirst() throws Exception {
        final String csv = mockMvc.perform(get("/api/v1/documents/d1/audit-trail").with(signedIn("auditor", "AUDITOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        final String[] lines = csv.split("\n");
        assertEquals("timestamp,source,documentId,actor,action,outcome,resourceType,resourceId,details,philterDocumentId,"
                + "piiType,startPosition,hash,previousHash,documentHash,policyName,policyVersion,signed,signingKeyId,"
                + "readError,chainVerified", lines[0]);
        assertTrue(lines[1].startsWith(",PHILTER_LEDGER_CHAIN,d1,,CHAIN_VERIFICATION,VERIFIED,"), lines[1]);
        assertTrue(lines[1].endsWith(",p1,,,,,,,,,,,true"), lines[1]);
        assertTrue(lines[2].startsWith("2026-10-31T12:00:00Z,PHILTER_LEDGER,d1,,REDACTION,"), lines[2]);
        assertTrue(lines[2].contains(",p1,ssn,14,h1,,dh,court,3,true,k1,,true"), lines[2]);
        assertTrue(lines[3].startsWith("2026-10-31T12:01:00Z,PHILTER_WORKSPACE,d1,reviewer@example.com,"
                + "DOCUMENT_APPROVAL,SUCCESS,Document,d1,"), lines[3]);

        final InOrder order = inOrder(auditLogService, trailService);
        order.verify(auditLogService).log(eq("AUDIT_TRAIL_EXPORT"), eq("Document"), eq("d1"),
                eq(Map.of("format", "csv")));
        order.verify(trailService).forDocument(document);
    }

    @Test
    void theJsonStatesHowEachChainVerified() throws Exception {
        final String body = mockMvc.perform(get("/api/v1/documents/d1/audit-trail").param("format", "json")
                .with(signedIn("boss", "ADMIN"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        final JsonNode json = new ObjectMapper().readTree(body);
        assertEquals("d1", json.at("/scope/documentId").asString());
        assertTrue(json.at("/chains/0/verified").asBoolean());
        assertEquals("Verified: the hash chain and every signature check.", json.at("/chains/0/summary").asString());
        assertEquals("PHILTER_LEDGER", json.at("/events/0/source").asString());
        assertEquals("ssn", json.at("/events/0/piiType").asString());
        assertTrue(json.at("/events/0/replacement").isMissingNode(), "no replacement");
        assertEquals("DOCUMENT_APPROVAL", json.at("/events/1/action").asString());
        assertFalse(json.at("/eventsTruncated").asBoolean());
    }

    @Test
    void aRangeTrailIsReadAndRecordedWithWhatItHolds() throws Exception {
        mockMvc.perform(get("/admin/audit/trail").param("startTime", "2026-10-31T00:00:00Z")
                        .param("endTime", "2026-11-01T00:00:00Z").param("format", "json")
                        .with(signedIn("auditor", "AUDITOR")))
                .andExpect(status().isOk());
        verify(trailService).forRange(Instant.parse("2026-10-31T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
        verify(auditLogService).log(eq("AUDIT_TRAIL_EXPORT"), eq("AuditLog"), isNull(), eq(Map.of(
                "format", "json", "startTime", "2026-10-31T00:00:00Z", "endTime", "2026-11-01T00:00:00Z",
                "events", 2, "chains", 1, "verifiedChains", 1L, "truncated", false)));
    }

    @Test
    void aBadFormatOrRangeIsRefusedBeforeAnythingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/documents/d1/audit-trail").param("format", "xml")
                .with(signedIn("boss", "ADMIN"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/audit/trail").param("format", "pdf").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/audit/trail").param("startTime", "2026-11-01T00:00:00Z")
                .param("endTime", "2026-10-31T00:00:00Z").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/documents/nope/audit-trail").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isNotFound());
        verify(trailService, never()).forDocument(any());
        verify(trailService, never()).forRange(any(), any());
        verify(auditLogService, never()).log(any(), any(), any(), anyMap());
    }

    @Test
    void theReviewPagesLedgerIsRecordedAsRead() throws Exception {
        final String body = mockMvc.perform(get("/api/v1/documents/d1/ledger").with(signedIn("auditor", "AUDITOR")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final JsonNode json = new ObjectMapper().readTree(body);
        assertEquals("p1", json.at("/chains/0/philterDocumentId").asString());
        assertTrue(json.at("/chains/0/verified").asBoolean());
        assertEquals("ssn", json.at("/entries/0/type").asString());
        verify(auditLogService).log("PHILTER_LEDGER_READ", "Document", "d1", Map.of("chains", 1, "entries", 1));
    }
}
