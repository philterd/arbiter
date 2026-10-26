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

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.PhilterInstanceClients;
import ai.philterd.arbiter.service.PhilterInstanceTls;
import ai.philterd.arbiter.service.SymmetricCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Admin → Philter Test button, against a stub Philter 4.0. */
class AdminPhilterControllerConnectionTest {

    private final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int limitsStatus = 200;
    private String callerRole = "user";
    private PhilterInstanceRepository repository;
    private PhilterInstance instance;
    private AdminPhilterController controller;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            final boolean health = exchange.getRequestURI().getPath().equals("/api/health");
            final int status = health ? 200 : limitsStatus;
            final byte[] body = (health ? "{\"status\":\"UP\",\"applicationVersion\":\"4.0.0\"}"
                    : "{\"caller\":{\"role\":\"" + callerRole + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        instance = new PhilterInstance();
        instance.setId("p1");
        instance.setName("philter");
        instance.setEndpoint("127.0.0.1");
        instance.setPort(server.getAddress().getPort());
        instance.setEncryptedApiKey(cipher.encrypt("sk_test"));
        repository = mock(PhilterInstanceRepository.class);
        when(repository.findById("p1")).thenReturn(Optional.of(instance));

        final DataSourceHostAllowList allowList = new DataSourceHostAllowList("127.0.0.1");
        controller = new AdminPhilterController(repository, mock(AuditLogService.class),
                mock(PhilterDefaultsService.class), cipher, allowList,
                new PhilterInstanceClients(cipher, allowList, new PhilterInstanceTls(cipher, PhilterTlsConfig.none())),
                new PhilterInstanceTls(cipher, PhilterTlsConfig.none()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void healthyInstanceWithAcceptedKeyPasses() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test("p1", ra);

        assertNull(ra.getFlashAttributes().get("error"));
        final String success = String.valueOf(ra.getFlashAttributes().get("success"));
        assertTrue(success.contains("Philter 4.0.0"), success);
        assertTrue(success.contains("API key accepted"), success);
        assertEquals("/api/health", paths.get(0));
        assertTrue(paths.stream().noneMatch(p -> p.equals("/api/status")));
    }

    @Test
    void rejectedKeyFails() {
        limitsStatus = 401;
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test("p1", ra);

        assertTrue(String.valueOf(ra.getFlashAttributes().get("error")).contains("API key rejected"));
    }

    @Test
    void administratorsKeyFailsAndIsFlagged() {
        callerRole = "admin";
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test("p1", ra);

        assertTrue(String.valueOf(ra.getFlashAttributes().get("error")).contains("belongs to a Philter administrator"));
        assertEquals(Boolean.TRUE, instance.getApiKeyAdmin());
    }

    @Test
    void usersKeyPassesAndIsMarkedChecked() {
        controller.test("p1", new RedirectAttributesModelMap());
        assertEquals(Boolean.FALSE, instance.getApiKeyAdmin());
    }

    @Test
    void administratorsKeyIsNotSaved() {
        callerRole = "admin";
        final String before = instance.getEncryptedApiKey();
        final RedirectAttributes change = new RedirectAttributesModelMap();
        controller.setApiKey("p1", "sk_admins_key", change);
        assertEquals(AdminPhilterController.ADMIN_KEY, change.getFlashAttributes().get("error"));
        assertEquals(before, instance.getEncryptedApiKey());

        final RedirectAttributes create = new RedirectAttributesModelMap();
        controller.create("another", "127.0.0.1", server.getAddress().getPort(), "sk_admins_key", create);
        assertEquals(AdminPhilterController.ADMIN_KEY, create.getFlashAttributes().get("error"));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void usersKeyIsSavedAndUncheckedKeyIsSavedWithANote() {
        final RedirectAttributes saved = new RedirectAttributesModelMap();
        controller.setApiKey("p1", "sk_users_key", saved);
        assertEquals("sk_users_key", cipher.decrypt(instance.getEncryptedApiKey()));
        assertEquals(Boolean.FALSE, instance.getApiKeyAdmin());

        server.stop(0);
        final RedirectAttributes unchecked = new RedirectAttributesModelMap();
        controller.setApiKey("p1", "sk_other_key", unchecked);
        assertTrue(String.valueOf(unchecked.getFlashAttributes().get("success")).contains("could not be reached"));
        assertEquals(null, instance.getApiKeyAdmin());
    }
}
