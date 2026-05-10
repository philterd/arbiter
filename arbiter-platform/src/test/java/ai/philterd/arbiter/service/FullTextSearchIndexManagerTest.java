/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the FTS index bootstrap. Stands up an in-process HTTP server
 * that pretends to be OpenSearch — the manager makes real TCP requests against it,
 * which catches bugs that pure-mock tests miss (auth header construction, JSON body
 * shape, status-code branching).
 */
class FullTextSearchIndexManagerTest {

    private MockOpenSearch server;
    private FullTextSearchIndexManager manager;

    @BeforeEach
    void setUp() throws Exception {
        server = MockOpenSearch.start();
        manager = new FullTextSearchIndexManager();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // ---------- HEAD branch: index does not exist ----------

    @Test
    void createsIndexWhenItDoesNotExist() {
        server.respondTo("HEAD", "/arbiter-documents", 404, "");
        server.respondTo("PUT", "/arbiter-documents", 200,
                "{\"acknowledged\":true,\"index\":\"arbiter-documents\"}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);

        assertEquals(FullTextSearchIndexManager.Outcome.CREATED, r.outcome());
        assertTrue(r.message().contains("created"));
        // The PUT body must include the canonical mapping under a "mappings" key.
        final String body = server.lastBodyFor("PUT", "/arbiter-documents");
        assertNotNull(body, "Manager should have POSTed a mapping body to OpenSearch.");
        assertTrue(body.contains("\"mappings\""));
        assertTrue(body.contains("\"originalText\""));
        assertTrue(body.contains("\"type\":\"text\""));
    }

    // ---------- HEAD branch: index exists, mapping matches ----------

    @Test
    void treatsExistingIndexWithMatchingMappingAsAlreadyMatches() {
        server.respondTo("HEAD", "/arbiter-documents", 200, "");
        server.respondTo("GET", "/arbiter-documents/_mapping", 200,
                "{\"arbiter-documents\":{\"mappings\":{\"properties\":{"
                        + "\"id\":{\"type\":\"keyword\"},"
                        + "\"batchId\":{\"type\":\"keyword\"},"
                        + "\"filename\":{\"type\":\"keyword\"},"
                        + "\"status\":{\"type\":\"keyword\"},"
                        + "\"originalText\":{\"type\":\"text\"}}}}}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);

        assertEquals(FullTextSearchIndexManager.Outcome.ALREADY_MATCHES, r.outcome());
        assertNull(r.existingMappingJson());
        assertNull(r.expectedMappingJson());
        // The manager must NOT issue a PUT when the mapping matches.
        assertEquals(0, server.callCountFor("PUT", "/arbiter-documents"));
    }

    @Test
    void extraFieldsOnExistingIndexAreToleratedAsMatching() {
        // Real-world OpenSearch indexes accumulate fields over time. As long as every
        // canonical field is present with the canonical type, additional properties on
        // the existing index do not block use.
        server.respondTo("HEAD", "/arbiter-documents", 200, "");
        server.respondTo("GET", "/arbiter-documents/_mapping", 200,
                "{\"arbiter-documents\":{\"mappings\":{\"properties\":{"
                        + "\"id\":{\"type\":\"keyword\"},"
                        + "\"batchId\":{\"type\":\"keyword\"},"
                        + "\"filename\":{\"type\":\"keyword\"},"
                        + "\"status\":{\"type\":\"keyword\"},"
                        + "\"originalText\":{\"type\":\"text\"},"
                        + "\"customField\":{\"type\":\"long\"}}}}}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.ALREADY_MATCHES, r.outcome(),
                "Extra fields on the existing index must not block a match — the mapping is a "
                        + "minimal contract, not a full equality check.");
    }

    // ---------- HEAD branch: index exists, mapping differs ----------

    @Test
    void detectsMappingMismatchWhenAFieldHasTheWrongType() {
        // The existing index has originalText as a keyword (no full-text indexing) — that's
        // a real, observable mismatch with the canonical "text" mapping.
        server.respondTo("HEAD", "/arbiter-documents", 200, "");
        server.respondTo("GET", "/arbiter-documents/_mapping", 200,
                "{\"arbiter-documents\":{\"mappings\":{\"properties\":{"
                        + "\"id\":{\"type\":\"keyword\"},"
                        + "\"batchId\":{\"type\":\"keyword\"},"
                        + "\"filename\":{\"type\":\"keyword\"},"
                        + "\"status\":{\"type\":\"keyword\"},"
                        + "\"originalText\":{\"type\":\"keyword\"}}}}}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);

        assertEquals(FullTextSearchIndexManager.Outcome.MAPPING_MISMATCH, r.outcome());
        assertNotNull(r.existingMappingJson(),
                "Mismatch must surface the existing mapping so the operator can review it.");
        assertNotNull(r.expectedMappingJson(),
                "Mismatch must surface the expected mapping for the side-by-side diff.");
        assertTrue(r.existingMappingJson().contains("\"keyword\""));
        assertTrue(r.expectedMappingJson().contains("\"text\""));
    }

    @Test
    void detectsMappingMismatchWhenACanonicalFieldIsMissing() {
        server.respondTo("HEAD", "/arbiter-documents", 200, "");
        // No originalText property at all — must fail the comparison.
        server.respondTo("GET", "/arbiter-documents/_mapping", 200,
                "{\"arbiter-documents\":{\"mappings\":{\"properties\":{"
                        + "\"id\":{\"type\":\"keyword\"}}}}}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.MAPPING_MISMATCH, r.outcome(),
                "Missing canonical field must produce a mismatch outcome, not silently pass.");
    }

    // ---------- error / unreachable paths ----------

    @Test
    void treatsUnreachableServerAsUnreachableOutcome() {
        // Stop the server first, then ask the manager to talk to that port.
        final int closedPort = server.port();
        server.stop();
        server = null;

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                "http://127.0.0.1:" + closedPort, "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.UNREACHABLE, r.outcome());
        assertTrue(r.message().contains("Could not reach"));
    }

    @Test
    void treatsServerErrorOnHeadAsServerError() {
        // 500 from HEAD — a transient cluster issue. The manager must surface it as a
        // server error rather than try to PUT against a broken cluster.
        server.respondTo("HEAD", "/arbiter-documents", 500, "boom");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.SERVER_ERROR, r.outcome());
        assertTrue(r.message().contains("HTTP 500"));
    }

    @Test
    void treatsServerErrorOnGetMappingAsServerError() {
        server.respondTo("HEAD", "/arbiter-documents", 200, "");
        server.respondTo("GET", "/arbiter-documents/_mapping", 502, "bad gateway");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.SERVER_ERROR, r.outcome());
    }

    @Test
    void treatsServerErrorOnPutAsServerError() {
        server.respondTo("HEAD", "/arbiter-documents", 404, "");
        server.respondTo("PUT", "/arbiter-documents", 400, "{\"error\":\"invalid mapping\"}");

        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.SERVER_ERROR, r.outcome());
        assertTrue(r.message().contains("Could not create"));
    }

    // ---------- auth header ----------

    @Test
    void appliesBasicAuthHeaderWhenUsernameAndPasswordAreSupplied() {
        server.respondTo("HEAD", "/arbiter-documents", 404, "");
        server.respondTo("PUT", "/arbiter-documents", 200, "{\"acknowledged\":true}");

        manager.ensureIndex(base(), "arbiter-documents", "alice", "s3cret");

        // OpenSearch security plugin wants Authorization: Basic base64(user:pass).
        final String header = server.lastAuthHeaderFor("HEAD", "/arbiter-documents");
        assertNotNull(header, "Auth header missing on HEAD request when credentials supplied.");
        assertTrue(header.startsWith("Basic "));
        final String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                StandardCharsets.UTF_8);
        assertEquals("alice:s3cret", decoded);

        // The PUT must carry the same header so a partial auth does not silently fail.
        assertNotNull(server.lastAuthHeaderFor("PUT", "/arbiter-documents"));
    }

    @Test
    void omitsAuthHeaderWhenUsernameIsBlank() {
        server.respondTo("HEAD", "/arbiter-documents", 404, "");
        server.respondTo("PUT", "/arbiter-documents", 200, "{\"acknowledged\":true}");

        manager.ensureIndex(base(), "arbiter-documents", "  ", "ignored");

        assertNull(server.lastAuthHeaderFor("HEAD", "/arbiter-documents"),
                "Blank username must not produce an Authorization header (defense against typos).");
    }

    @Test
    void trailingSlashOnEndpointIsNormalised() {
        server.respondTo("HEAD", "/arbiter-documents", 404, "");
        server.respondTo("PUT", "/arbiter-documents", 200, "{\"acknowledged\":true}");

        // base URL with a trailing slash — manager must trim it so the assembled URL is
        // not "http://host//arbiter-documents".
        final FullTextSearchIndexManager.Result r = manager.ensureIndex(
                base() + "/", "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.CREATED, r.outcome());
    }

    // ---------- forceCreate ----------

    @Test
    void forceCreateAlwaysIssuesPut() {
        server.respondTo("PUT", "/arbiter-documents", 200, "{\"acknowledged\":true}");

        final FullTextSearchIndexManager.Result r = manager.forceCreate(
                base(), "arbiter-documents", null, null);
        assertEquals(FullTextSearchIndexManager.Outcome.CREATED, r.outcome());
        assertEquals(1, server.callCountFor("PUT", "/arbiter-documents"));
        // forceCreate skips the HEAD probe.
        assertEquals(0, server.callCountFor("HEAD", "/arbiter-documents"));
    }

    // ---------- canonicalMappingJson ----------

    @Test
    void canonicalMappingJsonContainsAllExpectedFields() {
        final String json = manager.canonicalMappingJson();
        // All five canonical fields are present.
        for (String field : new String[]{"id", "batchId", "filename", "status", "originalText"}) {
            assertTrue(json.contains("\"" + field + "\""),
                    "Canonical mapping must declare the '" + field + "' field. Got: " + json);
        }
        // originalText is the only "text" field; everything else is a keyword.
        assertTrue(json.contains("\"type\":\"text\""));
        // No legacy "_doc" wrapper — modern OpenSearch mappings nest properties directly.
        assertFalse(json.contains("\"_doc\""));
    }

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    /**
     * Tiny in-process HTTP server pretending to be OpenSearch. Tests pre-register
     * canned (status, body) responses keyed by (method, path) and inspect inbound
     * requests via {@link #lastBodyFor}, {@link #callCountFor}, and
     * {@link #lastAuthHeaderFor}.
     */
    static final class MockOpenSearch {
        private final HttpServer http;
        private final java.util.Map<String, Canned> canned = new ConcurrentHashMap<>();
        private final java.util.Map<String, List<Recorded>> log = new ConcurrentHashMap<>();

        private MockOpenSearch(final HttpServer http) {
            this.http = http;
        }

        static MockOpenSearch start() throws IOException {
            final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final MockOpenSearch self = new MockOpenSearch(http);
            http.createContext("/", self::handle);
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "mock-os");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            return self;
        }

        int port() { return http.getAddress().getPort(); }
        void stop() { http.stop(0); }

        void respondTo(final String method, final String path, final int status, final String body) {
            canned.put(method + " " + path, new Canned(status, body));
        }

        synchronized String lastBodyFor(final String method, final String path) {
            final List<Recorded> list = log.get(method + " " + path);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1).body;
        }

        synchronized int callCountFor(final String method, final String path) {
            final List<Recorded> list = log.get(method + " " + path);
            return list == null ? 0 : list.size();
        }

        synchronized String lastAuthHeaderFor(final String method, final String path) {
            final List<Recorded> list = log.get(method + " " + path);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1).authHeader;
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final String method = exchange.getRequestMethod();
            final String path = exchange.getRequestURI().getPath();
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (this) {
                log.computeIfAbsent(method + " " + path, k -> new ArrayList<>())
                        .add(new Recorded(body, auth));
            }
            final Canned c = canned.getOrDefault(method + " " + path, new Canned(404, ""));
            final byte[] payload = c.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // HEAD must not write a body — it's just a status check on the server side.
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(c.status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(c.status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        record Canned(int status, String body) {}
        record Recorded(String body, String authHeader) {}
    }
}
