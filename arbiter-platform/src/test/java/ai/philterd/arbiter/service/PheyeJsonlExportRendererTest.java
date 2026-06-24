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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PheyeJsonlExportRendererTest {

    private final PheyeJsonlExportRenderer renderer = new PheyeJsonlExportRenderer();
    private final ObjectMapper mapper = new ObjectMapper();

    private static Span span(final String status, final String type, final int start, final int end) {
        final Span s = new Span();
        s.setStatus(status);
        s.setType(type);
        s.setLocation(new Location(start, end, 0, null));
        return s;
    }

    private static Document document(final String text) {
        final Document d = new Document();
        d.setId("doc-1");
        d.setOriginalText(text);
        return d;
    }

    @Test
    void mapsTextAndApprovedSpanOffsetsAndLabel() throws Exception {
        final String text = "Contact John Smith today.";
        final int start = text.indexOf("John Smith");
        final int end = start + "John Smith".length();
        final Document doc = document(text);

        final String line = renderer.render(doc, List.of(span("APPROVED", "name", start, end)));
        final JsonNode root = mapper.readTree(line);

        assertEquals(text, root.get("text").asText());
        assertEquals(1, root.get("spans").size());
        final JsonNode s = root.get("spans").get(0);
        assertEquals(start, s.get("start").asInt());
        assertEquals(end, s.get("end").asInt());
        assertEquals("name", s.get("label").asText());
        // The span object carries exactly start, end, and label (no extra fields).
        assertEquals(3, s.size());
        assertFalse(s.has("text"));
        // Offsets are valid against the exported text.
        assertEquals("John Smith", text.substring(s.get("start").asInt(), s.get("end").asInt()));
    }

    @Test
    void emptyApprovedSpansStillEmitLineWithEmptyArray() throws Exception {
        final String line = renderer.render(document("nothing sensitive here"), List.of());
        final JsonNode root = mapper.readTree(line);

        assertEquals("nothing sensitive here", root.get("text").asText());
        assertTrue(root.get("spans").isArray());
        assertEquals(0, root.get("spans").size());
    }

    @Test
    void includesOnlyApprovedSpans() throws Exception {
        final Document doc = document("a b c d e f g h i j");
        final List<Span> spans = List.of(
                span("APPROVED", "name", 0, 1),
                span("REJECTED", "name", 2, 3),
                span(Span.STATUS_NEEDS_SECOND_OPINION, "name", 4, 5),
                span("PENDING", "name", 6, 7),
                span("APPROVED", "ssn", 8, 9));

        final JsonNode root = mapper.readTree(renderer.render(doc, spans));

        assertEquals(2, root.get("spans").size(), "only the two APPROVED spans should be exported");
        assertEquals(0, root.get("spans").get(0).get("start").asInt());
        assertEquals(8, root.get("spans").get(1).get("start").asInt());
    }

    @Test
    void skipsSpansWithNoLocation() throws Exception {
        final Span noLocation = new Span();
        noLocation.setStatus("APPROVED");
        noLocation.setType("name");
        // location left null
        final JsonNode root = mapper.readTree(renderer.render(document("text"), List.of(noLocation)));
        assertEquals(0, root.get("spans").size());
    }

    @Test
    void outputIsASingleWellFormedJsonlLineWithEscapedNewlines() throws Exception {
        final String text = "line one\nline two";
        final String line = renderer.render(document(text), List.of(span("APPROVED", "name", 0, 4)));

        // A JSONL line must not contain a raw newline; the newline in the source text is escaped.
        assertFalse(line.contains("\n"), "the rendered line must not contain a raw newline");
        // The escaped newline round-trips through a standard JSON parser.
        assertEquals(text, mapper.readTree(line).get("text").asText());
    }

    @Test
    void nullOriginalTextRendersAsEmptyString() throws Exception {
        final JsonNode root = mapper.readTree(renderer.render(document(null), List.of()));
        assertEquals("", root.get("text").asText());
    }
}
