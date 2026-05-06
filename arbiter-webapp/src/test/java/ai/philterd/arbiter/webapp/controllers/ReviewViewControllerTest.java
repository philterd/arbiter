/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Coordinates;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.FinalizationPolicy;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.FinalizationPolicyRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.ApprovalRuleEvaluator;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DocumentLockService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.services.RedactionCertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the finalize / download / review-gating behaviors that key off source-availability
 * and the FinalizationPolicy applied at finalize time. Other pieces of the controller
 * (review-page rendering, span editing, locks, second-opinion plumbing) are out of scope here.
 */
class ReviewViewControllerTest {

    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private ComplianceProfileRepository complianceProfileRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private OllamaInstanceRepository ollamaInstanceRepository;
    private LlmJudgeDefaultsService llmJudgeDefaultsService;
    private UserSettingsService userSettingsService;
    private UserRepository userRepository;
    private ApprovalRuleEvaluator approvalRuleEvaluator;
    private DocumentLockService documentLockService;
    private OpenSearchIndexService openSearchIndexService;
    private RedactionCertificateService redactionCertificateService;
    private FinalizationPolicyRepository finalizationPolicyRepository;
    private ReviewViewController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        complianceProfileRepository = mock(ComplianceProfileRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        ollamaInstanceRepository = mock(OllamaInstanceRepository.class);
        llmJudgeDefaultsService = mock(LlmJudgeDefaultsService.class);
        userSettingsService = mock(UserSettingsService.class);
        userRepository = mock(UserRepository.class);
        approvalRuleEvaluator = mock(ApprovalRuleEvaluator.class);
        documentLockService = mock(DocumentLockService.class);
        openSearchIndexService = mock(OpenSearchIndexService.class);
        redactionCertificateService = mock(RedactionCertificateService.class);
        finalizationPolicyRepository = mock(FinalizationPolicyRepository.class);

        controller = new ReviewViewController(documentRepository, spanRepository, batchRepository,
                complianceProfileRepository, userGroupsService, auditLogService,
                ollamaInstanceRepository, llmJudgeDefaultsService, userSettingsService,
                userRepository, approvalRuleEvaluator, openSearchIndexService, documentLockService,
                redactionCertificateService, finalizationPolicyRepository);

        // Certificate generation always returns a stub so the audit-log payload doesn't NPE.
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setId("cert-1");
        cert.setDocumentHash("hash-1");
        when(redactionCertificateService.generate(any(), any())).thenReturn(cert);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }

    private static Document approvedDoc(final String id, final String batchId, final String original) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setStatus("APPROVED");
        d.setFilename(id + ".txt");
        d.setOriginalText(original);
        return d;
    }

    private static Span approvedSpan(final String id, final String docId, final String type,
                                     final int start, final int end, final String text) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        s.setType(type);
        s.setStatus("APPROVED");
        s.setText(text);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    // ---------- review() gating ----------

    @Test
    void reviewReturns409WhenSourceCleared() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.review("d1", admin(), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalization policy"));
    }

    @Test
    void reviewReturns409WhenSourceEmpty() {
        final Document d = approvedDoc("d1", "b1", "");
        d.setStatus("FINALIZED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.review("d1", admin(), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ---------- finalizeDocument() ----------

    @Test
    void finalizeRejectsNonApproved() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStatus("REVIEW_REQUIRED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("d1", admin(), ra);
        assertEquals("redirect:/review/d1", view);
        assertEquals("Only APPROVED documents can be finalized.", error(ra));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void finalizeRedirectsToQueueOnSuccess() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("d1", admin(), ra);
        assertEquals("redirect:/queue", view);
    }

    @Test
    void finalizeMissingDocumentRedirectsToQueue() {
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("ghost", admin(), ra);
        assertEquals("redirect:/queue", view);
        assertEquals("Document not found.", error(ra));
    }

    @Test
    void finalizePersistsRenderedRedactedTextAndChangesStatus() {
        final Document d = approvedDoc("d1", "b1", "Hi Bob, your SSN is 123-45-6789.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(
                approvedSpan("s1", "d1", "ssn", 20, 31, "123-45-6789"))));

        controller.finalizeDocument("d1", admin(), flash());

        final ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(saved.capture());
        assertEquals("FINALIZED", saved.getValue().getStatus());
        assertEquals("Hi Bob, your SSN is <<SSN>>.", saved.getValue().getRedactedText());
    }

    @Test
    void finalizeAuditsDocumentFinalize() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        controller.finalizeDocument("d1", admin(), flash());

        verify(auditLogService).log(eq("DOCUMENT_FINALIZE"), eq("Document"), eq("d1"), any());
    }

    // ---------- applyFinalizationPolicy() via finalizeDocument() ----------

    @Test
    void legalHoldPolicyDoesNotMutateDocumentAndAudits() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId("fp-hold");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp-hold");
        policy.setName("Legal Hold");
        policy.setOption(FinalizationPolicy.OPTION_LEGAL_HOLD);
        when(finalizationPolicyRepository.findById("fp-hold")).thenReturn(Optional.of(policy));

        controller.finalizeDocument("d1", admin(), flash());

        // Source/storage untouched.
        assertEquals("hello", d.getOriginalText());
        verify(spanRepository, never()).deleteByDocumentId(anyString());
        verify(documentRepository, never()).deleteById(anyString());

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("FINALIZATION_POLICY_APPLIED", "d1");
        assertEquals("RETAIN", details.getValue().get("action"));
        assertEquals("LEGAL_HOLD", details.getValue().get("option"));
        assertEquals("Legal Hold", details.getValue().get("policyName"));
    }

    @Test
    void deleteImmediatelyClearsSourceAndDeletesSpansButKeepsDocument() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStoragePath("/tmp/d1.txt");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId("fp-del");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp-del");
        policy.setName("Delete Immediately");
        policy.setOption(FinalizationPolicy.OPTION_DELETE_IMMEDIATELY);
        when(finalizationPolicyRepository.findById("fp-del")).thenReturn(Optional.of(policy));
        when(spanRepository.deleteByDocumentId("d1")).thenReturn(3L);

        controller.finalizeDocument("d1", admin(), flash());

        // Source content is wiped but the Document record is kept (queue still shows it,
        // certificate stays linkable).
        assertNull(d.getOriginalText());
        assertNull(d.getStoragePath());
        verify(spanRepository).deleteByDocumentId("d1");
        verify(documentRepository, never()).deleteById(anyString());
        // documentRepository.save() is called twice — once for FINALIZED + redactedText,
        // once after the source clear.
        verify(documentRepository, times(2)).save(d);

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("FINALIZATION_POLICY_APPLIED", "d1");
        assertEquals("SOURCE_CLEARED", details.getValue().get("action"));
        assertEquals("DELETE_IMMEDIATELY", details.getValue().get("option"));
        assertEquals(3L, details.getValue().get("spansDeleted"));
    }

    @Test
    void noBatchPolicyMeansNoFinalizationAuditOrMutation() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId(null);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        controller.finalizeDocument("d1", admin(), flash());

        verify(finalizationPolicyRepository, never()).findById(anyString());
        verify(auditLogService, never()).log(eq("FINALIZATION_POLICY_APPLIED"), any(), any(), any());
        // Source untouched.
        assertEquals("hello", d.getOriginalText());
    }

    // ---------- download() ----------

    @Test
    void downloadReturns409WhenNotFinalized() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.download("d1", admin()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalized"));
    }

    @Test
    void downloadUsesPersistedRedactedText() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        d.setRedactedText("Hi Bob, your SSN is <<SSN>>.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseEntity<Resource> response = controller.download("d1", admin());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final byte[] body = ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray();
        assertEquals("Hi Bob, your SSN is <<SSN>>.", new String(body, java.nio.charset.StandardCharsets.UTF_8));
        assertNotNull(response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("d1_redacted.txt"));
        // Spans should NOT be queried when redactedText is already cached on the document.
        verify(spanRepository, never()).findByDocumentId(anyString());
    }

    @Test
    void downloadFallsBackToRenderingWhenRedactedTextNotPersisted() {
        // Legacy doc: FINALIZED prior to the redactedText persistence change.
        final Document d = approvedDoc("d1", "b1", "Hi Bob, your SSN is 123-45-6789.");
        d.setStatus("FINALIZED");
        d.setRedactedText(null);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(
                approvedSpan("s1", "d1", "ssn", 20, 31, "123-45-6789"))));

        final ResponseEntity<Resource> response = controller.download("d1", admin());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final String body = new String(
                ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hi Bob, your SSN is <<SSN>>.", body);
    }

    @Test
    void downloadAuditsDocumentDownload() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        d.setRedactedText("redacted");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        controller.download("d1", admin());

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("DOCUMENT_DOWNLOAD", "d1");
        assertEquals("admin@x.com", details.getValue().get("actor"));
        assertEquals("d1_redacted.txt", details.getValue().get("filename"));
        assertEquals(8, details.getValue().get("bytes"));
    }

    @Test
    void downloadDoesNotIncludeRejectedSpans() {
        final Document d = approvedDoc("d1", "b1", "alpha bravo charlie");
        d.setStatus("FINALIZED");
        d.setRedactedText(null);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final Span rejected = approvedSpan("s1", "d1", "name", 6, 11, "bravo");
        rejected.setStatus("REJECTED");
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(rejected)));

        final ResponseEntity<Resource> response = controller.download("d1", admin());
        final String body = new String(
                ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        // Rejected span text is preserved verbatim; only APPROVED spans are masked.
        assertEquals("alpha bravo charlie", body);
        assertFalse(body.contains("<<NAME>>"));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> capturingDetails(final String action, final String entityId) {
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(action), eq("Document"), eq(entityId), captor.capture());
        return captor;
    }
}
