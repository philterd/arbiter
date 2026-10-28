/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.philter.PhilterTlsConfig;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.webapp.security.BootstrapKeyWarning;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin → Philter, against a stub Philter 4.0. */
class AdminPhilterControllerTest {

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int limitsStatus = 200;
    private String callerRole = "user";
    private AuditLogService auditLogService;
    private PhilterSettings philter;
    private AdminPhilterController controller;
    private BootstrapKeyWarning bootstrapKeyWarning;
    private String version = "4.0.0";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            final boolean health = exchange.getRequestURI().getPath().equals("/api/health");
            final int status = health ? 200 : limitsStatus;
            final byte[] body = (health ? "{\"status\":\"UP\",\"applicationVersion\":\"" + version + "\"}"
                    : callerRole == null ? "{}" : "{\"caller\":{\"role\":\"" + callerRole + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        philter = new PhilterSettings("http://127.0.0.1:" + server.getAddress().getPort(), "sk_test",
                PhilterTlsConfig.none());
        auditLogService = mock(AuditLogService.class);
        bootstrapKeyWarning = mock(BootstrapKeyWarning.class);
        controller = new AdminPhilterController(philter, auditLogService, bootstrapKeyWarning);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void showsTheConfiguredPhilterButNotTheKey() {
        final ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("admin-philter", controller.view(null, model));
        assertEquals(philter.url(), model.get("philterUrl"));
        assertTrue(model.values().stream().noneMatch("sk_test"::equals));
    }

    @Test
    void healthyPhilterWithAcceptedKeyPasses() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test(ra);

        assertNull(ra.getFlashAttributes().get("error"));
        final String success = String.valueOf(ra.getFlashAttributes().get("success"));
        assertTrue(success.contains("Philter 4.0.0"), success);
        assertTrue(success.contains("API key accepted"), success);
        assertEquals("/api/health", paths.get(0));
        assertEquals(Boolean.FALSE, philter.apiKeyAdmin());
        verify(auditLogService).log(eq("PHILTER_TEST"), eq("Philter"), any(), any());
    }

    @Test
    void rejectedKeyFails() {
        limitsStatus = 401;
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test(ra);

        assertTrue(String.valueOf(ra.getFlashAttributes().get("error")).contains("API key rejected"));
    }

    @Test
    void administratorsKeyFailsAndIsFlagged() {
        callerRole = "admin";
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test(ra);

        assertTrue(String.valueOf(ra.getFlashAttributes().get("error")).contains("belongs to a Philter administrator"));
        assertEquals(Boolean.TRUE, philter.apiKeyAdmin());
        final ExtendedModelMap model = new ExtendedModelMap();
        controller.view(null, model);
        assertEquals(Boolean.TRUE, model.get("apiKeyAdmin"));
    }

    @Test
    void saysWhenPhilterDoesNotReportTheRole() {
        callerRole = null;
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test(ra);
        assertTrue(String.valueOf(ra.getFlashAttributes().get("success")).contains("did not report its role"));
    }

    @Test
    void unreachablePhilterFails() {
        server.stop(0);
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test(ra);
        assertTrue(String.valueOf(ra.getFlashAttributes().get("error")).contains("failed the test"));
    }

    @Test
    void anUnsupportedPhilterFailsTheTest() {
        version = "5.0.0";
        final RedirectAttributes redirect = new RedirectAttributesModelMap();
        controller.test(redirect);
        final String error = String.valueOf(redirect.getFlashAttributes().get("error"));
        assertTrue(error.contains("version 5.0.0") && error.contains("supports Philter 4.x"), error);
    }

    @Test
    void anAdministratorIsWarnedWhileTheBootstrapKeyIsActive() {
        when(bootstrapKeyWarning.show(any())).thenReturn(true);
        final ExtendedModelMap model = new ExtendedModelMap();
        controller.view(null, model);
        assertEquals(Boolean.TRUE, model.get("bootstrapKeyActive"));
        assertEquals(BootstrapKeyWarning.DOCS_URL, model.get("bootstrapKeyDocs"));
    }
}
