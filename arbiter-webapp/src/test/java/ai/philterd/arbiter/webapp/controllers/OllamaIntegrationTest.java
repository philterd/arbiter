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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.DocumentAccessService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Arbiter's Ollama client surface in {@link LlmJudgeController}.
 *
 * Each test stands up a small in-process HTTP server on {@code 127.0.0.1} that pretends to
 * be an Ollama instance. The {@code OllamaInstance} row points at that server's host and
 * port, so the controller's real {@code HttpClient} actually issues requests over a TCP
 * socket and parses the canned JSON responses. This catches bugs that pure-mock tests
 * cannot — request bodies, JSON handling, status code mapping, and the BAD_GATEWAY
 * fallback when Ollama returns a non-2xx or invalid payload.
 *
 * The server records the inbound request body for each path so tests can assert that the
 * controller sent the expected {@code model}, {@code prompt}, and {@code stream:false}
 * payload.
 */
class OllamaIntegrationTest {

    private MockOllamaServer server;
    private OllamaInstanceRepository ollamaInstanceRepository;
    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private LlmJudgeDefaultsService llmJudgeDefaultsService;
    private LlmJudgeController controller;

    private OllamaInstance instance;

    @BeforeEach
    void setUp() throws Exception {
        server = MockOllamaServer.start();
        ollamaInstanceRepository = mock(OllamaInstanceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        llmJudgeDefaultsService = mock(LlmJudgeDefaultsService.class);
        controller = new LlmJudgeController(
                ollamaInstanceRepository, documentRepository, spanRepository,
                userGroupsService,
                new DocumentAccessService(batchRepository, documentRepository,
                        new BatchAccessService(batchRepository, userGroupsService)),
                auditLogService, llmJudgeDefaultsService, new ObjectMapper(),
                new DataSourceHostAllowList("127.0.0.1"));

        instance = new OllamaInstance();
        instance.setId("inst-mock");
        instance.setName("mock-ollama");
        instance.setEndpoint("127.0.0.1");
        instance.setPort(server.port());
        when(ollamaInstanceRepository.findById("inst-mock")).thenReturn(Optional.of(instance));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ---------- listModels ----------

    @Test
    void listModelsReturnsSortedDistinctNamesFromTags() {
        // Mixed-case names returned in arbitrary order — controller sorts case-insensitively.
        server.respondToTags(200,
                "{\"models\":[{\"name\":\"qwen:7b\"},{\"name\":\"Llama3:8b\"},{\"name\":\"mistral:7b\"}]}");

        final Map<String, Object> result = controller.listModels("inst-mock", admin());

        assertEquals("inst-mock", result.get("instanceId"));
        assertEquals("mock-ollama", result.get("instanceName"));
        assertEquals(List.of("Llama3:8b", "mistral:7b", "qwen:7b"), result.get("models"));
    }

    @Test
    void listModelsHandlesEmptyModelList() {
        server.respondToTags(200, "{\"models\":[]}");

        final Map<String, Object> result = controller.listModels("inst-mock", admin());
        assertEquals(List.of(), result.get("models"));
    }

    @Test
    void listModelsHandlesMissingModelsField() {
        server.respondToTags(200, "{}");

        final Map<String, Object> result = controller.listModels("inst-mock", admin());
        assertEquals(List.of(), result.get("models"));
    }

    @Test
    void listModelsSkipsBlankNames() {
        server.respondToTags(200,
                "{\"models\":[{\"name\":\"llama3\"},{\"name\":\"\"},{\"name\":\"   \"}]}");

        @SuppressWarnings("unchecked")
        final List<String> models = (List<String>) controller.listModels("inst-mock", admin()).get("models");
        assertEquals(List.of("llama3"), models);
    }

    @Test
    void listModelsTreatsServerErrorAsBadGateway() {
        server.respondToTags(500, "internal server error");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-mock", admin()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("HTTP 500"),
                "BAD_GATEWAY reason should preserve the upstream status code; got: " + ex.getReason());
    }

    @Test
    void listModelsTreatsMalformedJsonAsBadGateway() {
        server.respondToTags(200, "{not valid json");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("inst-mock", admin()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Could not reach"));
    }

    @Test
    void listModelsRejectsUnknownInstance() {
        when(ollamaInstanceRepository.findById("ghost")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listModels("ghost", admin()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- explain ----------

    @Test
    void explainReturnsParsedResponseFromOllama() {
        seedDocument("doc-1", "alice@example.com - she lives in Boston");
        server.respondToGenerate(200, "{\"response\":\"This text contains a name and a city.\"}");

        final Map<String, Object> out = controller.explain("doc-1",
                new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin());

        assertEquals("mock-ollama", out.get("instanceName"));
        assertEquals("llama3", out.get("model"));
        assertEquals("This text contains a name and a city.", out.get("response"));
    }

    @Test
    void explainPostsToGenerateWithExpectedBodyShape() throws Exception {
        seedDocumentWithSpan("doc-1", "alice@example.com",
                "email-address", 0, 17, "alice@example.com");
        server.respondToGenerate(200, "{\"response\":\"ok\"}");

        controller.explain("doc-1",
                new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin());

        final MockOllamaServer.Recorded sent = server.lastGenerateCall();
        assertNotNull(sent, "Expected a recorded /api/generate request");
        assertEquals("POST", sent.method);
        assertEquals("application/json", sent.contentType,
                "Generate calls must declare JSON content type so a server with strict body parsing accepts them.");

        final JsonNode body = new ObjectMapper().readTree(sent.body);
        assertEquals("llama3", body.get("model").asText());
        assertFalse(body.get("stream").asBoolean(),
                "Streaming must be disabled — the controller reads the full response body in one shot.");
        final String prompt = body.get("prompt").asText();
        assertTrue(prompt.contains("personally identifiable information"),
                "Prompt must include the PII review preamble; got: " + prompt);
        assertTrue(prompt.contains("alice@example.com"),
                "Prompt must include the document text being reviewed.");
        assertTrue(prompt.contains("email-address"),
                "Prompt must include the type of each detected span.");
    }

    @Test
    void explainAuditsBeforeMakingTheCall() {
        seedDocument("doc-1", "hello");
        // Ollama returns 500 — the audit log must still record that PII was sent because the
        // network packet has already left the box by the time the response is read.
        server.respondToGenerate(500, "boom");

        assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc-1",
                        new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin()));

        verify(auditLogService).log(eq("DOCUMENT_PII_SENT_TO_LLM"), eq("Document"),
                eq("doc-1"), any());
    }

    @Test
    void explainAuditsExactlyOnceWhenSuccessful() {
        seedDocument("doc-1", "hello");
        server.respondToGenerate(200, "{\"response\":\"yep\"}");

        controller.explain("doc-1",
                new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin());

        verify(auditLogService, times(1)).log(eq("DOCUMENT_PII_SENT_TO_LLM"),
                eq("Document"), eq("doc-1"), any());
    }

    @Test
    void explainAuditDetailsCarrySpanCount() {
        seedDocumentWithSpan("doc-1", "alice@example.com",
                "email-address", 0, 17, "alice@example.com");
        server.respondToGenerate(200, "{\"response\":\"ok\"}");

        controller.explain("doc-1",
                new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("DOCUMENT_PII_SENT_TO_LLM"), eq("Document"),
                eq("doc-1"), details.capture());
        assertEquals("inst-mock", details.getValue().get("instanceId"));
        assertEquals("mock-ollama", details.getValue().get("instanceName"));
        assertEquals("llama3", details.getValue().get("model"));
        assertEquals(1, details.getValue().get("spanCount"));
    }

    @Test
    void explainSendsNoRequestWhenInstanceIdBlank() {
        seedDocument("doc-1", "hello");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc-1",
                        new LlmJudgeController.ExplainRequest("", "llama3"), admin()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("instanceId is required"));
        assertEquals(0, server.generateCallCount(),
                "A bad-request must not produce an outbound /api/generate call.");
        verify(auditLogService, never()).log(eq("DOCUMENT_PII_SENT_TO_LLM"), any(), any(), any());
    }

    @Test
    void explainSendsNoRequestWhenModelBlank() {
        seedDocument("doc-1", "hello");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc-1",
                        new LlmJudgeController.ExplainRequest("inst-mock", "  "), admin()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("model is required"));
        assertEquals(0, server.generateCallCount());
        verify(auditLogService, never()).log(eq("DOCUMENT_PII_SENT_TO_LLM"), any(), any(), any());
    }

    @Test
    void explainOnDeletedInstanceFailsWithoutAuditing() {
        seedDocument("doc-1", "hello");
        when(ollamaInstanceRepository.findById("ghost")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain("doc-1",
                        new LlmJudgeController.ExplainRequest("ghost", "llama3"), admin()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(auditLogService, never()).log(eq("DOCUMENT_PII_SENT_TO_LLM"), any(), any(), any());
    }

    @Test
    void explainHandlesEmptyResponseField() {
        // A 200 with no "response" field maps to an empty string — defensive against an
        // Ollama variant that returns the streaming JSON envelope without a final response.
        seedDocument("doc-1", "hello");
        server.respondToGenerate(200, "{\"done\":true}");

        final Map<String, Object> out = controller.explain("doc-1",
                new LlmJudgeController.ExplainRequest("inst-mock", "llama3"), admin());
        assertEquals("", out.get("response"));
    }

    // ---------- second opinion ----------

    @Test
    void secondOpinionReturnsResponseAndPropagatesSpanContext() {
        final Span span = seedSpanForDocument("span-1", "doc-1", "alice@example.com",
                "person", 0, 5, "alice");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-mock", "llama3"));
        server.respondToGenerate(200, "{\"response\":\"likely a name\"}");

        final Map<String, Object> out = controller.secondOpinion("span-1", admin());

        assertEquals("mock-ollama", out.get("instanceName"));
        assertEquals("llama3", out.get("model"));
        assertEquals("alice", out.get("sourceText"));
        assertEquals("person", out.get("sourceType"));
        assertEquals("likely a name", out.get("response"));
    }

    @Test
    void secondOpinionPicksFirstAvailableModelWhenNoneConfigured() throws Exception {
        seedSpanForDocument("span-1", "doc-1", "context", "person", 0, 1, "x");
        // No model configured → controller queries /api/tags first, then /api/generate.
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-mock", null));
        server.respondToTags(200,
                "{\"models\":[{\"name\":\"qwen:7b\"},{\"name\":\"Llama3:8b\"}]}");
        server.respondToGenerate(200, "{\"response\":\"ok\"}");

        final Map<String, Object> out = controller.secondOpinion("span-1", admin());

        assertEquals("Llama3:8b", out.get("model"),
                "Controller picks the first model from the case-insensitive sort.");

        // Verify the generate request actually used that picked model.
        final JsonNode body = new ObjectMapper().readTree(server.lastGenerateCall().body);
        assertEquals("Llama3:8b", body.get("model").asText());
    }

    @Test
    void secondOpinionFailsWhenNoDefaultInstanceConfigured() {
        seedSpanForDocument("span-1", "doc-1", "context", "person", 0, 1, "x");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults(null, null));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("span-1", admin()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Admin → LLM-as-a-Judge"));
        assertEquals(0, server.generateCallCount());
    }

    @Test
    void secondOpinionFailsWhenDefaultInstanceWasDeleted() {
        seedSpanForDocument("span-1", "doc-1", "context", "person", 0, 1, "x");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-deleted", "llama3"));
        when(ollamaInstanceRepository.findById("inst-deleted")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("span-1", admin()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("no longer exists"));
    }

    @Test
    void secondOpinionFailsWhenServerHasNoModelsAvailable() {
        seedSpanForDocument("span-1", "doc-1", "context", "person", 0, 1, "x");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-mock", null));
        server.respondToTags(200, "{\"models\":[]}");

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("span-1", admin()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("no models installed"));
    }

    @Test
    void secondOpinionPromptIncludesSpanTypeTextAndConfidence() throws Exception {
        seedSpanForDocument("span-1", "doc-1", "Hi alice, your acct is 1234",
                "person", 3, 8, "alice");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-mock", "llama3"));
        server.respondToGenerate(200, "{\"response\":\"yes\"}");

        controller.secondOpinion("span-1", admin());

        final String prompt = new ObjectMapper().readTree(server.lastGenerateCall().body)
                .get("prompt").asText();
        assertTrue(prompt.contains("\"alice\""), "Prompt must quote the candidate PII text.");
        assertTrue(prompt.contains("person"), "Prompt must include the span type.");
        assertTrue(prompt.contains("Hi alice, your acct is 1234"),
                "Prompt must include the document context for the reviewer's judgment.");
    }

    @Test
    void secondOpinionAuditsOnceBeforeTheGenerateCall() {
        seedSpanForDocument("span-1", "doc-1", "context", "person", 0, 1, "x");
        when(llmJudgeDefaultsService.load()).thenReturn(defaults("inst-mock", "llama3"));
        server.respondToGenerate(500, "boom");

        assertThrows(ResponseStatusException.class,
                () -> controller.secondOpinion("span-1", admin()));

        verify(auditLogService, times(1)).log(eq("DOCUMENT_PII_SENT_TO_LLM"),
                eq("Document"), eq("doc-1"), any());
    }

    // ---------- helpers ----------

    private void seedDocument(final String docId, final String text) {
        final Document d = new Document();
        d.setId(docId);
        d.setBatchId("batch-1");
        d.setOriginalText(text);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId(docId)).thenReturn(List.of());
    }

    private void seedDocumentWithSpan(final String docId, final String text, final String type,
                                      final int start, final int end, final String spanText) {
        final Document d = new Document();
        d.setId(docId);
        d.setBatchId("batch-1");
        d.setOriginalText(text);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(d));
        final Span s = new Span();
        s.setId("span-" + docId);
        s.setDocumentId(docId);
        s.setType(type);
        s.setText(spanText);
        s.setConfidence(0.97);
        s.setStatus("APPROVED");
        s.setLocation(new ai.philterd.arbiter.model.Location(start, end, 1,
                new ai.philterd.arbiter.model.Coordinates(0, 0, 0, 0)));
        when(spanRepository.findByDocumentId(docId)).thenReturn(List.of(s));
    }

    private Span seedSpanForDocument(final String spanId, final String docId, final String docText,
                                     final String type, final int start, final int end, final String spanText) {
        final Document d = new Document();
        d.setId(docId);
        d.setBatchId("batch-1");
        d.setOriginalText(docText);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(d));

        final Span s = new Span();
        s.setId(spanId);
        s.setDocumentId(docId);
        s.setType(type);
        s.setText(spanText);
        s.setConfidence(0.91);
        s.setStatus("APPROVED");
        s.setLocation(new ai.philterd.arbiter.model.Location(start, end, 1,
                new ai.philterd.arbiter.model.Coordinates(0, 0, 0, 0)));
        when(spanRepository.findById(spanId)).thenReturn(Optional.of(s));
        return s;
    }

    private static LlmJudgeDefaults defaults(final String secondOpinionInstanceId, final String secondOpinionModel) {
        final LlmJudgeDefaults d = new LlmJudgeDefaults();
        d.setSecondOpinionInstanceId(secondOpinionInstanceId);
        d.setSecondOpinionModel(secondOpinionModel);
        return d;
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /**
     * Thin wrapper around the JDK's {@link HttpServer} that pretends to be Ollama. Tests
     * register canned responses for {@code /api/tags} and {@code /api/generate} via
     * {@link #respondToTags(int, String)} and {@link #respondToGenerate(int, String)}, then
     * inspect inbound traffic via {@link #lastGenerateCall()} and {@link #generateCallCount()}.
     */
    static final class MockOllamaServer {
        private final HttpServer http;
        private final Map<String, CannedResponse> canned = new ConcurrentHashMap<>();
        private final List<Recorded> generateCalls = new ArrayList<>();

        private MockOllamaServer(final HttpServer http) {
            this.http = http;
        }

        static MockOllamaServer start() throws IOException {
            final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final MockOllamaServer self = new MockOllamaServer(http);
            http.createContext("/api/tags", self::handleTags);
            http.createContext("/api/generate", self::handleGenerate);
            // Default 404 handler — anything else is unexpected and the test should fail fast.
            http.createContext("/api", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "mock-ollama");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            return self;
        }

        int port() {
            return http.getAddress().getPort();
        }

        void stop() {
            http.stop(0);
        }

        void respondToTags(final int status, final String json) {
            canned.put("tags", new CannedResponse(status, json));
        }

        void respondToGenerate(final int status, final String json) {
            canned.put("generate", new CannedResponse(status, json));
        }

        synchronized Recorded lastGenerateCall() {
            return generateCalls.isEmpty() ? null : generateCalls.get(generateCalls.size() - 1);
        }

        synchronized int generateCallCount() {
            return generateCalls.size();
        }

        private void handleTags(final HttpExchange exchange) throws IOException {
            sendCanned(exchange, "tags");
        }

        private void handleGenerate(final HttpExchange exchange) throws IOException {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final Recorded r = new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body);
            synchronized (this) {
                generateCalls.add(r);
            }
            sendCanned(exchange, "generate");
        }

        private void sendCanned(final HttpExchange exchange, final String key) throws IOException {
            final CannedResponse c = canned.getOrDefault(key, new CannedResponse(500, "{}"));
            final byte[] body = c.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(c.status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        record CannedResponse(int status, String body) {}
        record Recorded(String method, String contentType, String body) {}
    }
}
