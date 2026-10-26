/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.service.SignInSettings;
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.philter.model.User;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the Users API calls on the wire, against a stub Philter holding 150 users. */
class PhilterUsersServiceTest {

    private static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final int TOTAL = 150;

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();
    private PhilterUsersService service;
    private PhilterSignInService signInService;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + "?" + query
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization") + " body=" + body);
            String json = "{}";
            if ("GET".equals(exchange.getRequestMethod()) && "/api/users".equals(exchange.getRequestURI().getPath())) {
                final int offset = param(query, "offset");
                final int limit = param(query, "limit");
                final StringBuilder users = new StringBuilder();
                for (int i = offset; i < Math.min(offset + limit, TOTAL); i++) {
                    users.append(users.length() == 0 ? "" : ",").append("{\"username\":\"u").append(i)
                            .append("\",\"role\":\"user\",\"active\":true,\"passwordSet\":true}");
                }
                json = "{\"users\":[" + users + "],\"total\":" + TOTAL + "}";
            }
            final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/api/users")
                    && "POST".equals(exchange.getRequestMethod()) ? 201 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        signInService = new PhilterSignInService(
                new SignInSettings("philter", "http://127.0.0.1:" + server.getAddress().getPort()),
                PhilterTlsConfig.none(), new SymmetricCipher(TEST_KEY_B64));
        service = new PhilterUsersService(signInService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static int param(final String query, final String name) {
        final Matcher m = Pattern.compile("(?:^|&)" + name + "=(\\d+)").matcher(query);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Test
    void listsEveryPageWithTheAdministratorsKey() throws Exception {
        final List<User> users = service.listUsers(signInService.encrypt("sk_admin_session"));

        assertEquals(TOTAL, users.size());
        assertEquals("u149", users.get(TOTAL - 1).getUsername());
        assertEquals(2, seen.size(), "two pages of at most 100");
        assertTrue(seen.get(0).contains("limit=100"), seen.get(0));
        assertTrue(seen.stream().allMatch(s -> s.contains("auth=Bearer sk_admin_session")));
    }

    @Test
    void createsAUserWithAFirstPassword() throws Exception {
        service.createUser(signInService.encrypt("sk_admin_session"), "jdoe", "", "user", "first-password-1234");

        final String call = seen.get(0);
        assertTrue(call.startsWith("POST /api/users"), call);
        assertTrue(call.contains("\"username\":\"jdoe\"") && call.contains("\"role\":\"user\"")
                && call.contains("\"password\":\"first-password-1234\""), call);
        assertTrue(!call.contains("\"email\":\"\""), "a blank email is left out: " + call);
    }
}
