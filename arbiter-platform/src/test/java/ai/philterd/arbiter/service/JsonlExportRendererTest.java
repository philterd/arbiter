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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the public JSONL schema produced for {@code BATCH_EXPORT}. Downstream
 * pipelines that consume these files depend on the field names and shapes
 * staying the same — these assertions are the contract.
 */
class JsonlExportRendererTest {

    private final JsonlExportRenderer renderer = new JsonlExportRenderer();
    private final ObjectMapper mapper = new ObjectMapper();

    private static Document doc(final String id, final String text, final String... approvers) {
        final Document d = new Document();
        d.setId(id);
        d.setOriginalText(text);
        final List<String> list = new ArrayList<>();
        for (String a : approvers) list.add(a);
        d.setApprovedBy(list);
        return d;
    }

    private static Span span(final String type, final String text, final int start, final int end,
                             final double confidence, final String status) {
        final Span s = new Span();
        s.setType(type);
        s.setText(text);
        s.setLocation(new Location(start, end, 0, null));
        s.setConfidence(confidence);
        s.setStatus(status);
        return s;
    }

    @Test
    void rendersTextEntitiesAndMetadataInTheDocumentedShape() throws Exception {
        // Mirrors the example in the user-facing spec: text + entities[] +
        // metadata{doc_id, annotator, confidence}.
        final Document d = doc("DOC-001",
                "The patient, John Doe, was admitted on 2023-10-12 to Mayo Clinic.",
                "alice@example.com");
        final List<Span> spans = List.of(
                span("PERSON", "John Doe", 13, 21, 0.96, "APPROVED"),
                span("DATE", "2023-10-12", 39, 49, 1.00, "APPROVED"),
                span("ORG", "Mayo Clinic", 53, 64, 0.98, "APPROVED"));

        final String line = renderer.render(d, spans);
        assertFalse(line.contains("\n"), "JSONL line must not contain literal newlines");
        final JsonNode root = mapper.readTree(line);

        assertEquals("The patient, John Doe, was admitted on 2023-10-12 to Mayo Clinic.",
                root.get("text").asText());

        final JsonNode entities = root.get("entities");
        assertEquals(3, entities.size());
        assertEquals(13, entities.get(0).get("start").asInt());
        assertEquals(21, entities.get(0).get("end").asInt());
        assertEquals("PERSON", entities.get(0).get("label").asText());
        assertEquals("John Doe", entities.get(0).get("text").asText());

        final JsonNode metadata = root.get("metadata");
        assertEquals("DOC-001", metadata.get("doc_id").asText());
        assertEquals("alice@example.com", metadata.get("annotator").asText());
        // Average of 0.96, 1.00, 0.98 = 0.98
        assertEquals(0.98, metadata.get("confidence").asDouble(), 1e-9);
    }

    @Test
    void filtersOutNonApprovedSpans() throws Exception {
        // PENDING and REJECTED spans must not leak into entities — the export is
        // for "what reviewers signed off on", not "what the redactor saw".
        final Document d = doc("D1", "Hello world", "alice@x.com");
        final List<Span> spans = List.of(
                span("PERSON", "Hello", 0, 5, 0.9, "APPROVED"),
                span("ORG", "world", 6, 11, 0.8, "PENDING"),
                span("DATE", "world", 6, 11, 0.7, "REJECTED"));

        final String line = renderer.render(d, spans);
        final JsonNode root = mapper.readTree(line);
        assertEquals(1, root.get("entities").size(),
                "only APPROVED spans must show up in the entities array");
        assertEquals("PERSON", root.get("entities").get(0).get("label").asText());
        assertEquals(0.9, root.get("metadata").get("confidence").asDouble(), 1e-9);
    }

    @Test
    void emptyApprovedSpansProducesEmptyEntitiesAndOmitsConfidence() throws Exception {
        // A document approved with zero spans (reviewer rejected every detected
        // span and approved an unredacted document) is valid input. The output
        // line still has the standard shape; confidence is simply omitted
        // because there's nothing to average.
        final Document d = doc("D2", "no entities here", "bob@x.com");

        final String line = renderer.render(d, List.of());
        final JsonNode root = mapper.readTree(line);
        assertEquals(0, root.get("entities").size());
        assertFalse(root.get("metadata").has("confidence"),
                "confidence must be omitted when there are no approved spans");
        assertEquals("bob@x.com", root.get("metadata").get("annotator").asText());
    }

    @Test
    void usesFirstApproverAsAnnotator() throws Exception {
        // Dual-review: the first approver is the original annotator; subsequent
        // approvers are validators. The schema names a single annotator.
        final Document d = doc("D3", "x", "first@x.com", "second@x.com");

        final String line = renderer.render(d, List.of());
        final JsonNode root = mapper.readTree(line);
        assertEquals("first@x.com", root.get("metadata").get("annotator").asText());
    }

    @Test
    void escapesEmbeddedNewlinesAndQuotesInText() throws Exception {
        // Document text can contain \n and ". The renderer must escape them so
        // the output stays a single line of valid JSON — otherwise downstream
        // line-oriented readers would split on the embedded newline.
        final Document d = doc("D4", "Line 1\nLine \"2\"", "x@x.com");

        final String line = renderer.render(d, List.of());
        assertFalse(line.contains("\n"), "embedded newlines must be JSON-escaped, not raw");
        final JsonNode root = mapper.readTree(line);
        assertEquals("Line 1\nLine \"2\"", root.get("text").asText());
    }

    @Test
    void skipsSpansWithMissingLocation() throws Exception {
        // Defensive: a malformed span (missing Location) shouldn't sink the
        // whole row — it just gets dropped.
        final Document d = doc("D5", "Hello", "x@x.com");
        final Span bad = new Span();
        bad.setType("PERSON");
        bad.setText("Hello");
        bad.setStatus("APPROVED");
        // location intentionally null
        final Span good = span("ORG", "Hello", 0, 5, 0.9, "APPROVED");

        final String line = renderer.render(d, List.of(bad, good));
        final JsonNode root = mapper.readTree(line);
        assertEquals(1, root.get("entities").size());
        assertEquals("ORG", root.get("entities").get(0).get("label").asText());
    }

    @Test
    void roundTripsAsValidJsonl() throws Exception {
        // Two documents in a row with a '\n' between produces a 2-line JSONL.
        // Each line is independently parseable and the original data round-trips.
        final Document d1 = doc("a", "first text", "u@x.com");
        final Document d2 = doc("b", "second text", "u@x.com");
        final String jsonl = renderer.render(d1, List.of()) + "\n"
                + renderer.render(d2, List.of()) + "\n";

        final String[] lines = jsonl.split("\n");
        assertEquals(2, lines.length);
        assertEquals("a", mapper.readTree(lines[0]).get("metadata").get("doc_id").asText());
        assertEquals("b", mapper.readTree(lines[1]).get("metadata").get("doc_id").asText());
        assertTrue(mapper.readTree(lines[0]).get("entities").isArray());
    }
}
