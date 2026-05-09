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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that a {@code DOCUMENT_PII_SENT_TO_LLM} audit event is written before the
 * outbound HTTP call to Ollama — so the record exists even when Ollama is unreachable
 * or returns an error.
 */
class LlmJudgeControllerAuditTest {

    private OllamaInstanceRepository ollamaInstanceRepository;
    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private LlmJudgeDefaultsService llmJudgeDefaultsService;
    private LlmJudgeController controller;

    @BeforeEach
    void setUp() {
        ollamaInstanceRepository = mock(OllamaInstanceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        llmJudgeDefaultsService = mock(LlmJudgeDefaultsService.class);
        controller = new LlmJudgeController(ollamaInstanceRepository, documentRepository,
                spanRepository, batchRepository, userGroupsService, auditLogService,
                llmJudgeDefaultsService, new ObjectMapper());
    }

    // ----- fixtures -----

    private static Document doc(final String id, final String batchId) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setOriginalText("SSN 123-45-6789");
        return d;
    }

    private static Batch batch(final String id, final String groupId) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        return b;
    }

    /** Returns an OllamaInstance whose endpoint deliberately cannot be reached. */
    private static OllamaInstance unreachableInstance(final String id) {
        final OllamaInstance i = new OllamaInstance();
        i.setId(id);
        i.setName("test-ollama");
        i.setEndpoint("http://127.0.0.1");
        i.setPort(1); // port 1 — connection refused immediately
        return i;
    }

    private static Span span(final String id, final String documentId, final String type) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(documentId);
        s.setType(type);
        s.setText("123-45-6789");
        s.setConfidence(0.95);
        return s;
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // ----- explain endpoint -----

    @Test
    void explainLogsDocumentPiiSentBeforeHttpCall() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());

        // Ollama is unreachable → BAD_GATEWAY, but audit must still fire.
        assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1",
                        new LlmJudgeController.ExplainRequest("inst1", "llama3"),
                        admin()));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(
                eq("DOCUMENT_PII_SENT_TO_LLM"),
                eq("Document"),
                eq("doc1"),
                captor.capture());
        assertEquals("inst1", captor.getValue().get("instanceId"));
        assertEquals("llama3", captor.getValue().get("model"));
    }

    @Test
    void explainAuditIncludesSpanCount() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(
                List.of(span("s1", "doc1", "SSN"), span("s2", "doc1", "EMAIL")));

        assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1",
                        new LlmJudgeController.ExplainRequest("inst1", "llama3"),
                        admin()));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("DOCUMENT_PII_SENT_TO_LLM"), eq("Document"), eq("doc1"), captor.capture());
        assertEquals(2, captor.getValue().get("spanCount"));
    }

    @Test
    void explainAuditFiresBeforeOllamaResponseNotAfter() {
        // Ollama connection fails → we still want the audit, proving it fires BEFORE send().
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1",
                        new LlmJudgeController.ExplainRequest("inst1", "llama3"),
                        admin()));

        // BAD_GATEWAY means Ollama was unreachable, but the audit call already happened.
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        verify(auditLogService).log(eq("DOCUMENT_PII_SENT_TO_LLM"), anyString(), anyString(), any());
    }

    // ----- secondOpinion endpoint -----

    @Test
    void secondOpinionLogsDocumentPiiSentBeforeHttpCall() {
        final Span s = span("sp1", "doc1", "SSN");
        when(spanRepository.findById("sp1")).thenReturn(Optional.of(s));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));

        final LlmJudgeDefaults defaults = new LlmJudgeDefaults();
        defaults.setSecondOpinionInstanceId("inst1");
        defaults.setSecondOpinionModel("llama3");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults);
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));

        // Ollama unreachable → BAD_GATEWAY; audit must still fire.
        assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("sp1", admin()));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(
                eq("DOCUMENT_PII_SENT_TO_LLM"),
                eq("Document"),
                eq("doc1"), // resource is the parent document, not the span
                captor.capture());
        assertEquals("inst1", captor.getValue().get("instanceId"));
        assertEquals("sp1", captor.getValue().get("spanId"));
        assertEquals("SSN", captor.getValue().get("spanType"));
    }

    @Test
    void secondOpinionAuditResourceIsDocumentNotSpan() {
        // The audit event resource type must be "Document" because the full document text is sent.
        final Span s = span("sp1", "doc1", "EMAIL");
        when(spanRepository.findById("sp1")).thenReturn(Optional.of(s));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));

        final LlmJudgeDefaults defaults = new LlmJudgeDefaults();
        defaults.setSecondOpinionInstanceId("inst1");
        defaults.setSecondOpinionModel("llama3");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults);
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));

        assertThrows(ResponseStatusException.class, () -> controller.secondOpinion("sp1", admin()));

        verify(auditLogService).log(
                eq("DOCUMENT_PII_SENT_TO_LLM"),
                eq("Document"),
                eq("doc1"),
                any());
    }

    @Test
    void secondOpinionAuditFiresBeforeOllamaResponse() {
        final Span s = span("sp1", "doc1", "SSN");
        when(spanRepository.findById("sp1")).thenReturn(Optional.of(s));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));

        final LlmJudgeDefaults defaults = new LlmJudgeDefaults();
        defaults.setSecondOpinionInstanceId("inst1");
        defaults.setSecondOpinionModel("llama3");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults);
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("sp1", admin()));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        verify(auditLogService).log(eq("DOCUMENT_PII_SENT_TO_LLM"), anyString(), anyString(), any());
    }

    // ----- access-gate: no audit on rejected calls -----

    @Test
    void explainDoesNotAuditWhenDocumentAccessDenied() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "b1")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1", "g1")));
        // User is in g2, not g1 → 404 before any audit.
        when(userGroupsService.groupIdsForEmail("bob@x.com")).thenReturn(Set.of("g2"));
        when(ollamaInstanceRepository.findById("inst1")).thenReturn(Optional.of(unreachableInstance("inst1")));

        final Authentication bob = new UsernamePasswordAuthenticationToken("bob@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1",
                        new LlmJudgeController.ExplainRequest("inst1", "llama3"),
                        bob));

        // Access denied before audit point — no PII-sent event.
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }
}
