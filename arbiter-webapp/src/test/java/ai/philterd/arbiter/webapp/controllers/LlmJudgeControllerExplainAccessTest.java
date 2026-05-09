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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Authorization tests for {@link LlmJudgeController#explain} focused on the
 * Ollama-instance gate that was previously missing.
 *
 * <p>Before the fix, a reviewer who passed the document-access check could supply
 * any admin-registered Ollama {@code instanceId} and force the server to POST the
 * document's PII to that URL — including instances registered for unrelated batches.
 * The fix applies the same {@code requireListModelsAccess} gate used by
 * {@code listModels}, so reviewers are restricted to the configured Explain/Second-Opinion
 * default instances.
 */
class LlmJudgeControllerExplainAccessTest {

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
                spanRepository, userGroupsService,
                new ai.philterd.arbiter.service.DocumentAccessService(batchRepository, documentRepository,
                        new ai.philterd.arbiter.service.BatchAccessService(batchRepository, userGroupsService)),
                auditLogService, llmJudgeDefaultsService, new ObjectMapper(),
                new ai.philterd.arbiter.service.DataSourceHostAllowList("127.0.0.1"));
    }

    // ----- fixtures -----

    private static Document docInGroup(final String docId, final String batchId) {
        final Document d = new Document();
        d.setId(docId);
        d.setBatchId(batchId);
        d.setOriginalText("some text");
        return d;
    }

    private static Batch batchInGroup(final String batchId, final String groupId) {
        final Batch b = new Batch();
        b.setId(batchId);
        b.setGroupId(groupId);
        return b;
    }

    private static OllamaInstance instance(final String id) {
        final OllamaInstance i = new OllamaInstance();
        i.setId(id);
        i.setName("ollama-" + id);
        i.setEndpoint("http://127.0.0.1");
        i.setPort(1);
        return i;
    }

    private static LlmJudgeDefaults defaults(final String explainId, final String secondOpinionId) {
        final LlmJudgeDefaults d = new LlmJudgeDefaults();
        d.setExplainInstanceId(explainId);
        d.setSecondOpinionInstanceId(secondOpinionId);
        return d;
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private LlmJudgeController.ExplainRequest req(final String instanceId) {
        return new LlmJudgeController.ExplainRequest(instanceId, "llama3");
    }

    // ----- forbidden: instance gate -----

    @Test
    void reviewerCannotUseArbitraryInstanceEvenWithDocumentAccess() {
        // Reviewer has legitimate access to the document …
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        when(batchRepository.findById("batch1")).thenReturn(Optional.of(batchInGroup("batch1", "g1")));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        // … but requests an Ollama instance that is not a configured default.
        when(ollamaInstanceRepository.findById("inst-rogue"))
                .thenReturn(Optional.of(instance("inst-rogue")));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-rogue"), user("alice@x.com")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void reviewerWithNoGroupsIsForbiddenOnInstance() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        // Doc's batch has no group — document access check falls through for admin only;
        // non-admin gets 404 from requireDocumentAccess before the instance gate.
        // Use a batch that IS accessible to test the instance gate path independently.
        final Batch b = new Batch();
        b.setId("batch1");
        b.setGroupId("g1");
        when(batchRepository.findById("batch1")).thenReturn(Optional.of(b));
        // User has no groups → fails requireDocumentAccess first (same 404 as missing doc).
        when(userGroupsService.groupIdsForEmail("stranded@x.com")).thenReturn(Set.of());
        when(ollamaInstanceRepository.findById("inst-explain"))
                .thenReturn(Optional.of(instance("inst-explain")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-explain"), user("stranded@x.com")));
        // requireDocumentAccess fires first — returns 404 (group membership absent).
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ----- forbidden: document gate (unchanged behaviour) -----

    @Test
    void reviewerWithoutDocumentAccessIsForbidden() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        when(batchRepository.findById("batch1")).thenReturn(Optional.of(batchInGroup("batch1", "g1")));
        // User is in g2, not g1.
        when(userGroupsService.groupIdsForEmail("bob@x.com")).thenReturn(Set.of("g2"));
        when(ollamaInstanceRepository.findById("inst-explain"))
                .thenReturn(Optional.of(instance("inst-explain")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-explain"), user("bob@x.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ----- allowed cases -----

    @Test
    void reviewerWithDocumentAccessAndDefaultInstancePassesGate() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        when(batchRepository.findById("batch1")).thenReturn(Optional.of(batchInGroup("batch1", "g1")));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        when(ollamaInstanceRepository.findById("inst-explain"))
                .thenReturn(Optional.of(instance("inst-explain")));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());

        // Gate passes; unreachable Ollama → BAD_GATEWAY proves the gate was cleared.
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-explain"), user("alice@x.com")));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void reviewerWithDocumentAccessAndSecondOpinionDefaultPassesGate() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        when(batchRepository.findById("batch1")).thenReturn(Optional.of(batchInGroup("batch1", "g1")));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        when(ollamaInstanceRepository.findById("inst-second"))
                .thenReturn(Optional.of(instance("inst-second")));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-second"), user("alice@x.com")));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void adminPassesInstanceGateForAnyInstance() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(docInGroup("doc1", "batch1")));
        when(ollamaInstanceRepository.findById("inst-arbitrary"))
                .thenReturn(Optional.of(instance("inst-arbitrary")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());

        // Admin bypasses both document-access and instance gate; unreachable Ollama → BAD_GATEWAY.
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc1", req("inst-arbitrary"), admin()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }
}
