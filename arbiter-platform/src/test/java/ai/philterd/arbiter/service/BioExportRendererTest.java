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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the public BIO output shape produced for {@code BATCH_EXPORT}
 * jobs. Downstream NER training pipelines depend on the exact line format
 * (one token per line, tab-separated label, blank line between paragraphs)
 * — these assertions are the contract.
 */
class BioExportRendererTest {

    private final BioExportRenderer renderer = new BioExportRenderer();

    private static Document doc(final String text) {
        final Document d = new Document();
        d.setId("doc-1");
        d.setOriginalText(text);
        d.setApprovedBy(new ArrayList<>());
        return d;
    }

    private static Span span(final String type, final int start, final int end, final String status) {
        final Span s = new Span();
        s.setType(type);
        s.setStatus(status);
        s.setLocation(new Location(start, end, 0, null));
        return s;
    }

    @Test
    void rendersTokenPerLineWithTabSeparatedLabel() {
        // The opening line in the user-facing example: "The patient, John
        // Doe, was admitted on 2023-10-12 to Mayo Clinic." Verify each token
        // gets the right BIO label and the columns are tab-separated.
        final String text = "The patient, John Doe, was admitted on 2023-10-12 to Mayo Clinic.";
        // John = 13..17, Doe = 18..21, 2023-10-12 = 39..49, Mayo = 53..57, Clinic = 58..64
        final List<Span> spans = List.of(
                span("PERSON", 13, 17, "APPROVED"),
                span("PERSON", 18, 21, "APPROVED"),
                span("DATE", 39, 49, "APPROVED"),
                span("ORG", 53, 57, "APPROVED"),
                span("ORG", 58, 64, "APPROVED"));

        final String body = renderer.render(doc(text), spans);
        final String[] lines = body.split("\n", -1);

        // Last entry from split may be a trailing empty string; the rendered
        // text always ends with a newline so the final split element is "".
        assertEquals("The\tO", lines[0]);
        assertEquals("patient,\tO", lines[1]);
        assertEquals("John\tB-PERSON", lines[2]);
        assertEquals("Doe,\tB-PERSON", lines[3]);
        assertEquals("was\tO", lines[4]);
        assertEquals("admitted\tO", lines[5]);
        assertEquals("on\tO", lines[6]);
        assertEquals("2023-10-12\tB-DATE", lines[7]);
        assertEquals("to\tO", lines[8]);
        assertEquals("Mayo\tB-ORG", lines[9]);
        assertEquals("Clinic.\tB-ORG", lines[10]);
    }

    @Test
    void preservesParagraphBreaksAsBlankLines() {
        // Two paragraphs separated by a blank line in the source must stay
        // separated by a blank line in the BIO output — that's how most NER
        // loaders detect sentence/document boundaries.
        final String text = "Hello world.\n\nGoodbye world.";
        final String body = renderer.render(doc(text), List.of());

        final String[] lines = body.split("\n", -1);
        assertEquals("Hello\tO", lines[0]);
        assertEquals("world.\tO", lines[1]);
        assertEquals("", lines[2], "paragraph break must produce a blank line");
        assertEquals("Goodbye\tO", lines[3]);
        assertEquals("world.\tO", lines[4]);
    }

    @Test
    void filtersOutNonApprovedSpans() {
        // PENDING and REJECTED spans must not produce labels — the export is
        // for "what reviewers signed off on", not "what the redactor saw".
        final String text = "Alice and Bob";
        final List<Span> spans = List.of(
                span("PERSON", 0, 5, "APPROVED"),     // Alice → labeled
                span("PERSON", 10, 13, "PENDING"),    // Bob → ignored
                span("PERSON", 6, 9, "REJECTED"));    // and → ignored

        final String body = renderer.render(doc(text), spans);
        final String[] lines = body.split("\n", -1);
        assertEquals("Alice\tB-PERSON", lines[0]);
        assertEquals("and\tO", lines[1]);
        assertEquals("Bob\tO", lines[2]);
    }

    @Test
    void normalizesTypeNameToUppercaseLabel() {
        // Arbiter PII types are lowercase ("ssn", "first-name"); the BIO
        // label is uppercased, with non-alphanumerics collapsed to underscore.
        final String text = "X Y";
        final List<Span> spans = List.of(
                span("first-name", 0, 1, "APPROVED"),
                span("ssn", 2, 3, "APPROVED"));

        final String body = renderer.render(doc(text), spans);
        final String[] lines = body.split("\n", -1);
        assertEquals("X\tB-FIRST_NAME", lines[0]);
        assertEquals("Y\tB-SSN", lines[1]);
    }

    @Test
    void emptyDocumentProducesEmptyOutput() {
        assertEquals("", renderer.render(doc(""), List.of()));
        assertEquals("", renderer.render(doc(null), List.of()));
    }

    @Test
    void documentWithNoSpansLabelsEverythingO() {
        // A document approved with zero spans (reviewer rejected every detected
        // span) still exports cleanly — every token is "O".
        final String body = renderer.render(doc("a b c"), List.of());
        final String[] lines = body.split("\n", -1);
        assertEquals("a\tO", lines[0]);
        assertEquals("b\tO", lines[1]);
        assertEquals("c\tO", lines[2]);
    }

    @Test
    void tokenWithPunctuationStillTaggedWhenSpanCoversTheWord() {
        // Span covers "Doe" (3 chars); the token "Doe," is tagged because at
        // least one character of the token falls inside the span. This matches
        // the user-facing example where "Doe," gets B-PERSON despite the comma
        // being outside the recorded span boundary.
        final String text = "Doe, ok";
        final List<Span> spans = List.of(span("PERSON", 0, 3, "APPROVED"));

        final String body = renderer.render(doc(text), spans);
        assertTrue(body.startsWith("Doe,\tB-PERSON\n"),
                "punctuation-attached token should still be labeled when the word overlaps the span");
    }

    @Test
    void leadingWhitespaceDoesNotProduceBlankLineAtStart() {
        // A document that starts with a paragraph break shouldn't begin with
        // a blank line — that would confuse downstream loaders into thinking
        // the file starts with an empty document.
        final String body = renderer.render(doc("\n\nHello"), List.of());
        assertEquals("Hello\tO\n", body);
    }
}
