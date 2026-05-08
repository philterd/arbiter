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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization tests for {@link LlmJudgeController#listModels(String,
 * org.springframework.security.core.Authentication)}.
 *
 * <p>The endpoint makes an outbound HTTP call to an admin-configured Ollama URL. Without
 * the gate this would let any authenticated user — including a stranded API-key holder —
 * probe internal-only Ollama servers. The gate restricts callers to: admins (always); and
 * group-scoped reviewers calling for one of the configured Explain or Second-Opinion
 * default instances.
 *
 * <p>These tests assert the gate without actually hitting Ollama: the gate runs <em>before</em>
 * the HTTP call, so a forbidden caller never reaches it. Allowed callers return either
 * a success or a {@code BAD_GATEWAY} (since no Ollama is running in unit tests) — both
 * are post-gate outcomes that prove the gate didn't reject them.
 */
class LlmJudgeControllerListModelsAccessTest {

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

    private static OllamaInstance instance(final String id) {
        final OllamaInstance i = new OllamaInstance();
        i.setId(id);
        i.setName("ollama-" + id);
        // Use an unreachable address so the post-gate HTTP call always fails fast (BAD_GATEWAY).
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

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    // ---------- missing instance ----------

    @Test
    void missingInstanceReturns404RegardlessOfRole() {
        when(ollamaInstanceRepository.findById("ghost")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("ghost", admin()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        // Post-condition: defaults were never even consulted because the instance lookup
        // happens first.
        verify(llmJudgeDefaultsService, never()).load();
    }

    // ---------- forbidden cases ----------

    @Test
    void nonAdminWithNoGroupsIsForbidden() {
        when(ollamaInstanceRepository.findById("inst-1")).thenReturn(Optional.of(instance("inst-1")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-1", user("stranded@x.com")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        // Defaults are not even loaded — no group membership is the first failure.
        verify(llmJudgeDefaultsService, never()).load();
    }

    @Test
    void nonAdminCallingForNonDefaultInstanceIsForbidden() {
        when(ollamaInstanceRepository.findById("inst-other"))
                .thenReturn(Optional.of(instance("inst-other")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of("g1"));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-other", user("alice@x.com")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void anonymousIsForbidden() {
        // An anonymous identity might still reach the API filter chain in odd configs;
        // the gate must reject it just as it does a "logged in but stranded" user.
        when(ollamaInstanceRepository.findById("inst-1")).thenReturn(Optional.of(instance("inst-1")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-1", anonymous()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void nullAuthIsForbidden() {
        when(ollamaInstanceRepository.findById("inst-1")).thenReturn(Optional.of(instance("inst-1")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-1", null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---------- allowed cases ----------

    @Test
    void adminPassesGateRegardlessOfDefaults() {
        // Admin doesn't need to be in any group nor have the instance set as a default —
        // the LLM-as-a-Judge admin page lists models for any registered instance.
        when(ollamaInstanceRepository.findById("inst-arbitrary"))
                .thenReturn(Optional.of(instance("inst-arbitrary")));

        // The gate passes; the post-gate HTTP call fails with BAD_GATEWAY because the
        // endpoint is unreachable. That's the proof that the gate let the caller through.
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-arbitrary", admin()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        // No defaults consulted for admins.
        verify(llmJudgeDefaultsService, never()).load();
    }

    @Test
    void groupScopedUserCallingForExplainDefaultPassesGate() {
        when(ollamaInstanceRepository.findById("inst-explain"))
                .thenReturn(Optional.of(instance("inst-explain")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of("g1"));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-explain", user("alice@x.com")));
        // Past the gate → outbound HTTP attempt fails with BAD_GATEWAY (proof of pass).
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void groupScopedUserCallingForSecondOpinionDefaultPassesGate() {
        when(ollamaInstanceRepository.findById("inst-second"))
                .thenReturn(Optional.of(instance("inst-second")));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(Set.of("g1"));
        when(llmJudgeDefaultsService.load())
                .thenReturn(defaults("inst-explain", "inst-second"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-second", user("alice@x.com")));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }
}
