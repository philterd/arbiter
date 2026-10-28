/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import ai.philterd.workspace.philter.PhilterTlsConfig;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks what goes over the wire to Philter, against a stub HTTP server. */
class PhilterSignInServiceTest {

    private static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();
    private PhilterSignInService service;
    private volatile int bootstrapStatus = 200;
    private volatile boolean bootstrapActive = true;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " xff=" + exchange.getRequestHeaders().getFirst("X-Forwarded-For")
                    + " body=" + body);
            final String path = exchange.getRequestURI().getPath();
            final String json;
            final int status;
            if ("/api/sign-in".equals(path)) {
                status = 200;
                json = "{\"apiKey\":\"sk_session_abc\",\"username\":\"jdoe\"}";
            } else if ("/api/health".equals(path)) {
                status = 200;
                json = "{\"status\":\"UP\",\"applicationVersion\":\"4.0.0\"}";
            } else if ("/api/users/admin/api-keys".equals(path)) {
                // Two pages: the bootstrap key is on the second.
                final boolean first = exchange.getRequestURI().getQuery().contains("offset=0");
                status = bootstrapStatus;
                json = status != 200 ? "{}" : first
                        ? "{\"apiKeys\":[{\"id\":\"k1\",\"bootstrap\":false}],\"total\":2}"
                        : "{\"apiKeys\":[{\"id\":\"k2\",\"bootstrap\":" + bootstrapActive + "}],\"total\":2}";
            } else if ("/api/users/me".equals(path)) {
                status = 200;
                json = "{\"username\":\"jdoe\",\"role\":\"user\",\"active\":true}";
            } else {
                status = 204;
                json = null;
            }
            if (json == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        final PhilterSettings settings = new PhilterSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                "sk_background", PhilterTlsConfig.none());
        service = new PhilterSignInService(settings, PhilterTlsConfig.none(), new SymmetricCipher(TEST_KEY_B64));

        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        server.stop(0);
    }

    @Test
    void signInSendsThePersonsAddressAndNoKey() throws Exception {
        final SignInResponse response = service.signIn("jdoe", "a-password-of-16+", "198.51.100.9", "Agent");

        assertEquals("sk_session_abc", response.getApiKey());
        final String call = seen.get(0);
        assertTrue(call.startsWith("POST /api/sign-in auth=null xff=198.51.100.9"), call);
    }

    @Test
    void callsWithAKeySendItDecryptedAsABearerTokenWithThePersonsAddress() throws Exception {
        final String encrypted = service.encrypt("sk_session_abc");
        assertNotEquals("sk_session_abc", encrypted);

        assertEquals("jdoe", service.currentUser(encrypted).getUsername());
        service.signOut(encrypted);

        assertTrue(seen.get(0).startsWith("GET /api/users/me auth=Bearer sk_session_abc xff=203.0.113.7"), seen.get(0));
        assertTrue(seen.get(1).startsWith("DELETE /api/api-keys/current auth=Bearer sk_session_abc xff=203.0.113.7"),
                seen.get(1));
    }

    @Test
    void healthIsReadWithoutAKey() throws Exception {
        assertEquals("4.0.0", service.health().getApplicationVersion());
        assertTrue(seen.get(0).startsWith("GET /api/health auth=null"), seen.get(0));
    }

    @Test
    void theBootstrapKeyIsLookedForOnEveryPageOfTheAdminUsersLongLivedKeys() throws Exception {
        final String encrypted = service.encrypt("sk_session_admin");

        assertTrue(service.bootstrapKeyActive(encrypted));
        assertEquals(2, seen.size(), "both pages were read");
        assertTrue(seen.get(0).startsWith("GET /api/users/admin/api-keys auth=Bearer sk_session_admin"), seen.get(0));

        bootstrapActive = false;
        assertFalse(service.bootstrapKeyActive(encrypted));

        bootstrapStatus = 404;
        assertFalse(service.bootstrapKeyActive(encrypted), "no admin user, so no bootstrap key");

        bootstrapStatus = 403;
        assertThrows(ClientException.class, () -> service.bootstrapKeyActive(encrypted));
    }
}
