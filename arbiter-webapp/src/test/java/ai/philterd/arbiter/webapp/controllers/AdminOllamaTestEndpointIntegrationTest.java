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

import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for the Admin → LLM-as-a-Judge "Test" button on
 * {@link AdminOllamaController}. Drives the controller against an in-process HTTP server
 * pretending to be Ollama. Covers happy path (any 2xx/3xx/4xx counts as "responded"),
 * the connection-refused branch, and the audit log payload shape — including {@code ok},
 * {@code status}, {@code detail}, and {@code url}.
 */
class AdminOllamaTestEndpointIntegrationTest {

    private TinyHttpServer server;
    private OllamaInstanceRepository repository;
    private AuditLogService auditLogService;
    private LlmJudgeDefaultsService defaultsService;
    private AdminOllamaController controller;

    @BeforeEach
    void setUp() throws Exception {
        server = TinyHttpServer.start();
        repository = mock(OllamaInstanceRepository.class);
        auditLogService = mock(AuditLogService.class);
        defaultsService = mock(LlmJudgeDefaultsService.class);
        controller = new AdminOllamaController(repository, auditLogService, defaultsService,
                new DataSourceHostAllowList("127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void testButtonReportsSuccessWhenServerResponds() {
        server.respondWith(200);
        final OllamaInstance inst = pointAtServer("inst-1");
        when(repository.findById("inst-1")).thenReturn(Optional.of(inst));

        final RedirectAttributes ra = flash();
        final String view = controller.test("inst-1", ra);

        assertEquals("redirect:/admin/llm-judge", view);
        assertNotNull(ra.getFlashAttributes().get("success"),
                "A 2xx response from the configured URL must produce a success flash.");
        assertNull(ra.getFlashAttributes().get("error"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("OLLAMA_INSTANCE_TEST"), eq("OllamaInstance"),
                eq("inst-1"), details.capture());
        assertEquals(true, details.getValue().get("ok"));
        assertEquals(200, details.getValue().get("status"));
        assertEquals("HTTP 200", details.getValue().get("detail"));
        assertTrue(((String) details.getValue().get("url")).startsWith("http://127.0.0.1:"));
    }

    @Test
    void testButtonReportsSuccessForAnyHttpResponse() {
        // The Test button calls /api on the Ollama base URL. A live Ollama returns 404 for
        // exactly that path (no handler registered there) — and that's still a healthy sign
        // that something is listening. The controller treats any HTTP status as "responded".
        server.respondWith(404);
        final OllamaInstance inst = pointAtServer("inst-2");
        when(repository.findById("inst-2")).thenReturn(Optional.of(inst));

        controller.test("inst-2", flash());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("OLLAMA_INSTANCE_TEST"), any(), any(), details.capture());
        assertEquals(true, details.getValue().get("ok"));
        assertEquals(404, details.getValue().get("status"));
    }

    @Test
    void testButtonReportsErrorWhenConnectionRefused() {
        // Stop the server so the port is unbound, then point the instance at it. The
        // outbound TCP connect will fail and the controller must surface an error flash.
        final int closedPort = server.port();
        server.stop();
        server = null;

        final OllamaInstance inst = new OllamaInstance();
        inst.setId("inst-down");
        inst.setName("down-ollama");
        inst.setEndpoint("127.0.0.1");
        inst.setPort(closedPort);
        when(repository.findById("inst-down")).thenReturn(Optional.of(inst));

        final RedirectAttributes ra = flash();
        controller.test("inst-down", ra);

        assertNotNull(ra.getFlashAttributes().get("error"),
                "Connection refused must surface as an error flash.");
        assertNull(ra.getFlashAttributes().get("success"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("OLLAMA_INSTANCE_TEST"), any(), eq("inst-down"), details.capture());
        assertEquals(false, details.getValue().get("ok"));
        assertEquals(0, details.getValue().get("status"),
                "When no HTTP exchange happened, status must be 0 (uninitialized).");
        // detail carries the underlying exception message; exact text is JVM-specific so
        // just assert it isn't blank.
        assertFalse(((String) details.getValue().get("detail")).isBlank());
    }

    @Test
    void testButtonRefusesUnknownInstance() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.test("ghost", ra);

        assertEquals("Ollama instance not found.", ra.getFlashAttributes().get("error"));
        verify(auditLogService, org.mockito.Mockito.never())
                .log(eq("OLLAMA_INSTANCE_TEST"), any(), any(), any());
    }

    @Test
    void testButtonRefusesInstanceWhoseHostIsNotOnTheAllowList() {
        // The allow-list above is "127.0.0.1". Point the instance at a public host and the
        // pre-flight allow-list check must reject the call without making a network request.
        final OllamaInstance inst = new OllamaInstance();
        inst.setId("inst-evil");
        inst.setName("evil-ollama");
        inst.setEndpoint("evil.example.com");
        inst.setPort(80);
        when(repository.findById("inst-evil")).thenReturn(Optional.of(inst));

        final RedirectAttributes ra = flash();
        controller.test("inst-evil", ra);

        assertEquals("Endpoint host is not permitted. Update or remove this instance.",
                ra.getFlashAttributes().get("error"));
        // No audit entry — the request never went out.
        verify(auditLogService, org.mockito.Mockito.never())
                .log(eq("OLLAMA_INSTANCE_TEST"), any(), any(), any());
        assertEquals(0, server.requestCount(),
                "An allow-list rejection must not result in an outbound HTTP call.");
    }

    @Test
    void testButtonHitsTheApiPathOnTheConfiguredBaseUrl() {
        server.respondWith(200);
        final OllamaInstance inst = pointAtServer("inst-3");
        when(repository.findById("inst-3")).thenReturn(Optional.of(inst));

        controller.test("inst-3", flash());

        assertEquals(1, server.requestCount(),
                "Test button must issue exactly one outbound request.");
        assertEquals("/api", server.lastPath(),
                "The probe path must be /api on the Ollama base URL.");
    }

    // ---------- helpers ----------

    private OllamaInstance pointAtServer(final String id) {
        final OllamaInstance inst = new OllamaInstance();
        inst.setId(id);
        inst.setName("mock-" + id);
        inst.setEndpoint("127.0.0.1");
        inst.setPort(server.port());
        return inst;
    }

    private static RedirectAttributes flash() {
        return new RedirectAttributesModelMap();
    }

    /**
     * Minimal HTTP server that responds with a fixed status to any request, recording the
     * total count and last requested path. The controller's probe is path-{@code /api}; that
     * test asserts the path on the last call.
     */
    static final class TinyHttpServer {
        private final HttpServer http;
        private final AtomicInteger status = new AtomicInteger(200);
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile String lastPath = "";

        private TinyHttpServer(final HttpServer http) {
            this.http = http;
        }

        static TinyHttpServer start() throws IOException {
            final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final TinyHttpServer self = new TinyHttpServer(http);
            http.createContext("/", self::handle);
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "tiny-http");
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

        void respondWith(final int code) {
            status.set(code);
        }

        int requestCount() {
            return count.get();
        }

        String lastPath() {
            return lastPath;
        }

        private void handle(final HttpExchange exchange) throws IOException {
            count.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
