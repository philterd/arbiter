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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests for {@link OpenSearchIndexService#sanitizeHighlight(String, String, String)} —
 * the per-snippet escape that protects the search-results template from stored XSS in the
 * indexed document text. The template uses {@code th:utext} on the snippet, so the snippet
 * must be HTML-safe when it leaves this method: only {@code <mark>} should ever survive
 * verbatim, and only where our private sentinel sat.
 */
class OpenSearchIndexServiceSanitizeHighlightTest {

    private static final String OPEN = "[[arbHL_test_OPEN]]";
    private static final String CLOSE = "[[arbHL_test_CLOSE]]";

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("hello world",
                OpenSearchIndexService.sanitizeHighlight("hello world", OPEN, CLOSE));
    }

    @Test
    void sentinelsAreReplacedWithMarkTags() {
        final String snippet = "the " + OPEN + "matched" + CLOSE + " term";
        assertEquals("the <mark>matched</mark> term",
                OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE));
    }

    @Test
    void inlineScriptInDocumentTextIsNeutralized() {
        // The classic stored-XSS payload that would have fired against th:utext under the old
        // behavior. After the fix, the angle brackets are escaped — the browser sees text.
        final String snippet = "<script>alert(1)</script> " + OPEN + "match" + CLOSE;
        final String safe = OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE);
        assertFalse(safe.contains("<script>"),
                "raw <script> survived: " + safe);
        assertTrue(safe.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(safe.contains("<mark>match</mark>"));
    }

    @Test
    void imgOnerrorPayloadIsNeutralized() {
        final String snippet = "look: <img src=x onerror=alert(1)> here " + OPEN + "match" + CLOSE;
        final String safe = OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE);
        assertFalse(safe.contains("<img"),
                "raw <img> survived: " + safe);
        assertTrue(safe.contains("&lt;img src=x onerror=alert(1)&gt;"));
    }

    @Test
    void ampersandIsEscapedExactlyOnce() {
        // & must be escaped first so that &lt; in the raw text doesn't get re-escaped
        // to &amp;lt; — and so that "fish & chips" comes out as "fish &amp; chips".
        final String snippet = "fish & chips and " + OPEN + "tea" + CLOSE;
        assertEquals("fish &amp; chips and <mark>tea</mark>",
                OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE));
    }

    @Test
    void preEscapedTextIsNotDoubleEscaped() {
        // Edge case: the document text already contains a literal "&lt;" sequence.
        // After our escape it becomes "&amp;lt;" — still inert in the browser, just shown
        // as text. This is the correct behavior; no double-decode in the rendered page.
        final String snippet = "see &lt;the docs&gt; for " + OPEN + "details" + CLOSE;
        final String safe = OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE);
        assertEquals("see &amp;lt;the docs&amp;gt; for <mark>details</mark>", safe);
    }

    @Test
    void userTextThatLooksLikeOurSentinelDoesNotGetMarked() {
        // Without the per-request UUID, a malicious uploader could type our own sentinel
        // into a document and trick the renderer into emitting <mark>. With a per-request
        // UUID embedded in the sentinel, this attack is infeasible. We exercise the simpler
        // case here: a *different* sentinel string in the user text is never replaced.
        final String userTypedFakeSentinel = "[[arbHL_GUESSED_OPEN]]hello[[arbHL_GUESSED_CLOSE]]";
        final String snippet = userTypedFakeSentinel + " " + OPEN + "real" + CLOSE;
        final String safe = OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE);
        // The user's sentinel survives as escaped literal text (brackets aren't HTML-special),
        // but it stays as text — it is *not* substituted for <mark>.
        assertTrue(safe.contains("[[arbHL_GUESSED_OPEN]]hello[[arbHL_GUESSED_CLOSE]]"),
                "user sentinel did not survive verbatim: " + safe);
        assertTrue(safe.contains("<mark>real</mark>"));
    }

    @Test
    void multipleHighlightTermsInOneSnippet() {
        final String snippet = OPEN + "Alpha" + CLOSE + " " + OPEN + "Beta" + CLOSE + " gamma";
        assertEquals("<mark>Alpha</mark> <mark>Beta</mark> gamma",
                OpenSearchIndexService.sanitizeHighlight(snippet, OPEN, CLOSE));
    }

    @Test
    void nullSnippetReturnsEmptyString() {
        assertEquals("", OpenSearchIndexService.sanitizeHighlight(null, OPEN, CLOSE));
    }

    @Test
    void emptySnippetReturnsEmptyString() {
        assertEquals("", OpenSearchIndexService.sanitizeHighlight("", OPEN, CLOSE));
    }
}
