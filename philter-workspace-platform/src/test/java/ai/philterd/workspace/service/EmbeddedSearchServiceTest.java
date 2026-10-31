/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The embedded Lucene full-text backend (#7). */
class EmbeddedSearchServiceTest {

    @TempDir
    Path dir;

    private final GeneralSettings settings = new GeneralSettings();
    private final GeneralSettingsService settingsService = mock(GeneralSettingsService.class);
    private final SecretKey key = new SecretKeySpec(new byte[32], "AES");
    private EmbeddedSearchService search;

    @BeforeEach
    void setUp() {
        settings.setFullTextSearchEnabled(true);
        when(settingsService.load()).thenReturn(settings);
        search = new EmbeddedSearchService(dir.resolve("index"), key, settingsService);
    }

    @AfterEach
    void tearDown() throws Exception {
        search.close();
    }

    private static Document doc(final String id, final String batchId, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setFilename(id + ".txt");
        d.setStatus("REVIEW_REQUIRED");
        d.setOriginalText(text);
        return d;
    }

    @Test
    void aMatchReturnsTheDocumentWithItsFieldsAndAnExactTotal() {
        search.indexDocument(doc("d1", "b1", "Jane Roe filed the motion on Monday."));
        search.indexDocument(doc("d2", "b1", "The court granted the motion."));
        search.indexDocument(doc("d3", "b2", "An unrelated invoice."));

        final DocumentSearchService.SearchResults r = search.search("motion", 0, 10);
        assertEquals(2, r.total());
        assertEquals(Set.of("d1", "d2"), Set.copyOf(r.hits().stream().map(DocumentSearchService.SearchHit::id).toList()));
        final DocumentSearchService.SearchHit d1 = r.hits().stream().filter(h -> h.id().equals("d1")).findFirst()
                .orElseThrow();
        assertEquals("b1", d1.batchId());
        assertEquals("d1.txt", d1.filename());
        assertEquals("REVIEW_REQUIRED", d1.status());

        final DocumentSearchService.SearchResults paged = search.search("motion", 1, 1);
        assertEquals(2, paged.total());
        assertEquals(1, paged.hits().size(), "the second page of one");
    }

    @Test
    void theBatchFilterLimitsBothHitsAndTotal() {
        search.indexDocument(doc("d1", "b1", "motion to dismiss"));
        search.indexDocument(doc("d2", "b2", "motion to compel"));

        final DocumentSearchService.SearchResults r = search.search("motion", 0, 10, List.of("b2"));
        assertEquals(1, r.total());
        assertEquals("d2", r.hits().get(0).id());
        assertEquals(0, search.search("motion", 0, 10, List.of()).total(), "no batches, no results");
        assertEquals(0, search.search("motion", 0, 10, List.of("b9")).total());
    }

    @Test
    void highlightsMarkTheMatchAndCannotCarryMarkupFromTheDocument() {
        search.indexDocument(doc("d1", "b1",
                "Note: <script>alert(1)</script> Jane Roe's <b>address</b> & phone. [[arbHL_x_OPEN]] Jane again."));

        final List<String> snippets = search.search("Jane", 0, 10).hits().get(0).highlights();
        assertFalse(snippets.isEmpty());
        final String all = String.join(" ", snippets);
        assertTrue(all.contains("<mark>Jane</mark>"), all);
        assertTrue(all.contains("&lt;script&gt;") && all.contains("&amp;"), all);
        assertFalse(all.contains("<script>") || all.contains("<b>"), all);
        assertFalse(all.replace("<mark>", "").replace("</mark>", "").contains("<"), "no tag but <mark>: " + all);
        assertTrue(all.contains("[[arbHL_x_OPEN]]"), "a forged sentinel stays text: " + all);
    }

    @Test
    void queryTextIsMatchedNotParsed() {
        search.indexDocument(doc("d1", "b1", "Section 5(a) applies AND controls."));
        assertEquals(1, search.search("5(a) AND OR NOT \"unclosed", 0, 10).total(), "no syntax error");
        assertEquals(0, search.search("   ", 0, 10).total());
        assertEquals(0, search.search("!!!", 0, 10).total(), "nothing to match");
    }

    @Test
    void aNearDuplicateRanksAboveAnUnrelatedDocumentAndTheSourceIsLeftOut() {
        search.indexDocument(doc("d1", "b1",
                "Plaintiff Jane Roe alleges breach of the lease agreement for the warehouse on Elm Street."));
        search.indexDocument(doc("d2", "b1",
                "Plaintiff Jane Roe alleges a breach of the lease agreement for the Elm Street warehouse."));
        search.indexDocument(doc("d3", "b1",
                "Quarterly invoice totals for the accounting department, with the street address of the warehouse."));
        search.indexDocument(doc("d4", "b2",
                "Plaintiff Jane Roe alleges breach of the lease agreement for the warehouse on Elm Street."));

        final DocumentSearchService.SearchResults r = search.findSimilar("d1", "b1", 10);
        final List<String> ids = r.hits().stream().map(DocumentSearchService.SearchHit::id).toList();
        assertFalse(ids.contains("d1"), "not the document itself");
        assertFalse(ids.contains("d4"), "only the same batch");
        assertEquals("d2", ids.get(0), ids.toString());
        if (ids.contains("d3")) {
            assertTrue(ids.indexOf("d2") < ids.indexOf("d3"));
        }
        assertEquals(0, search.findSimilar("missing", "b1", 10).total());
    }

    @Test
    void reindexingReplacesAndRemovingOrClearingDeletes() {
        search.indexDocument(doc("d1", "b1", "first version"));
        search.indexDocument(doc("d1", "b1", "second version"));
        assertEquals(0, search.search("first", 0, 10).total(), "replaced, not duplicated");
        assertEquals(1, search.search("second", 0, 10).total());

        search.indexDocument(doc("d2", "b1", "second copy"));
        assertTrue(search.removeDocument("d1"));
        assertTrue(search.removeDocument("never-indexed"));
        assertEquals(List.of("d2"), search.search("second", 0, 10).hits().stream()
                .map(DocumentSearchService.SearchHit::id).toList());

        assertTrue(search.clear());
        assertEquals(0, search.search("second", 0, 10).total());
    }

    @Test
    void theIndexIsOnDiskEncryptedAndSurvivesARestart() throws Exception {
        search.indexDocument(doc("d1", "b1", "Jane Roe zebracorn 123-45-6789"));
        search.close();

        try (Stream<Path> files = Files.list(dir.resolve("index"))) {
            for (Path f : files.toList()) {
                final String raw = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("zebracorn") || raw.contains("Jane Roe"), "plaintext in " + f.getFileName());
            }
        }
        search = new EmbeddedSearchService(dir.resolve("index"), key, settingsService);
        assertEquals(1, search.search("zebracorn", 0, 10).total());
    }

    @Test
    void whileSearchIsOffNothingIsIndexedOrOpened() {
        settings.setFullTextSearchEnabled(false);
        search.indexDocument(doc("d1", "b1", "anything"));
        assertEquals(0, search.search("anything", 0, 10).total());
        assertEquals(DocumentSearchService.State.DISABLED, search.probe());
        assertTrue(search.removeDocument("d1"));
        assertFalse(Files.exists(dir.resolve("index")), "the index is not created or locked");

        settings.setFullTextSearchEnabled(true);
        assertEquals(DocumentSearchService.State.REACHABLE, search.probe());
        assertEquals("embedded", search.backend());
    }
}
