/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.philter;

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhilterClientImplTest {

    private static final String TEXT = "SSN 123-45-6789 here";

    private static final String EXPLAIN_BODY = """
            {"filteredText":"SSN {{{REDACTED-ssn}}} here","context":"ctx","documentId":"d1",
             "explanation":{"appliedSpans":[{"characterStart":4,"characterEnd":15,"filterType":"SSN",
             "confidence":0.9,"text":"123-45-6789"}],"ignoredSpans":[]}}
            """;

    private HttpServer server;
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status = 200;
    private String documentIdHeader = "6543210fedcba9876543210f";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/explain", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            final byte[] body = (status == 200 ? EXPLAIN_BODY : "{\"message\":\"no\",\"reason\":\"policy_not_found\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (documentIdHeader != null) {
                exchange.getResponseHeaders().add("X-Document-Id", documentIdHeader);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PhilterClient client(final String apiKey, final String policy) {
        return new DefaultPhilterClientFactory(PhilterTlsConfig.none())
                .create("http://127.0.0.1:" + server.getAddress().getPort(), apiKey, policy);
    }

    @Test
    void explainSendsPolicyContextAndBearerKey() throws IOException {
        final Map<String, Object> result = client("secret", "claims").explain(TEXT, "ctx");

        assertTrue(query.get().contains("p=claims"));
        assertTrue(query.get().contains("c=ctx"));
        assertEquals("Bearer secret", authorization.get());

        final List<?> explanation = (List<?>) result.get("explanation");
        assertEquals(1, explanation.size());
        final Map<?, ?> span = (Map<?, ?>) explanation.get(0);
        assertEquals("ssn", span.get("type"));
        assertEquals(4, span.get("characterStart"));
        assertEquals(15, span.get("characterEnd"));
        assertEquals(0.9, span.get("confidence"));
    }

    @Test
    void blankPolicyAndContextAreOmitted() throws IOException {
        client(null, " ").explain(TEXT, "");

        assertTrue(query.get() == null || !query.get().contains("p="));
        assertTrue(query.get() == null || !query.get().contains("c="));
        assertEquals(null, authorization.get());
    }

    @Test
    void redactBuildsRedactionsInFinalText() throws IOException {
        final RedactionResponse response = client(null, null).redact(TEXT, "ctx");

        assertEquals("SSN SSN here", response.getRedactedText());
        assertEquals(1, response.getRedactions().size());
        final Redaction r = response.getRedactions().get(0);
        assertEquals(4, r.getStart());
        assertEquals(7, r.getEnd());
        assertEquals("123-45-6789", r.getText());
    }

    @Test
    void redactCarriesTheDocumentIdPhilterRecordedItUnder() throws IOException {
        // Philter sends the ID in the X-Document-Id header; the body's documentId is not it.
        assertEquals("6543210fedcba9876543210f", client("secret", "claims").redact(TEXT, "ctx").getPhilterDocumentId());

        documentIdHeader = null;
        assertEquals(null, client("secret", "claims").redact(TEXT, "ctx").getPhilterDocumentId());
    }

    @Test
    void failureMessageCarriesStatusButNotResponseText() {
        status = 404;
        final IOException e = assertThrows(IOException.class, () -> client(null, "missing").explain(TEXT, "ctx"));

        assertTrue(e.getMessage().contains("HTTP 404"));
        assertTrue(e.getMessage().contains("policy_not_found"));
        assertFalse(e.getMessage().contains("no\""));
        assertEquals(null, e.getCause());
    }

    @Test
    void philterEnumNamesMapToPhileasTypes() {
        assertEquals("ip-address", PhilterClientImpl.type(span("IP_ADDRESS")));
        assertEquals("id", PhilterClientImpl.type(span("IDENTIFIER")));
        assertEquals("person", PhilterClientImpl.type(span("PERSON")));
        assertEquals("new-type", PhilterClientImpl.type(span("NEW_TYPE")));
        assertEquals("other", PhilterClientImpl.type(span(null)));
    }

    @Test
    void factoryReusesSdkClientPerInstanceAndKey() throws IOException {
        final DefaultPhilterClientFactory factory = new DefaultPhilterClientFactory(PhilterTlsConfig.none());
        final String url = "http://127.0.0.1:" + server.getAddress().getPort();
        factory.create(url, "a", "p1").explain(TEXT, "ctx");
        assertEquals("Bearer a", authorization.get());
        factory.create(url, "b", "p1").explain(TEXT, "ctx");
        assertEquals("Bearer b", authorization.get());
        factory.create(url, "a", "p2").explain(TEXT, "ctx");
        assertEquals("Bearer a", authorization.get());
        assertTrue(query.get().contains("p=p2"));
    }

    private static ai.philterd.philter.model.Span span(final String filterType) {
        final ai.philterd.philter.model.Span span = new ai.philterd.philter.model.Span();
        span.setFilterType(filterType);
        return span;
    }

    @Test
    void approvedSpansAreAppliedWithoutCallingPhilter() throws IOException {
        final Redaction r = new Redaction();
        r.setStart(4);
        r.setEnd(15);
        r.setType("ssn");

        assertEquals("SSN SSN here", client(null, null).redact(TEXT, "ctx", List.of(r)));
        assertEquals(null, query.get());
    }
}
