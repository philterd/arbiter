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
import ai.philterd.workspace.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Choosing the full-text backend, and rebuilding its index from the database (#7). */
class SearchBackendSelectionTest {

    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final GeneralSettingsService settingsService = mock(GeneralSettingsService.class);

    @Test
    void theBackendIsChosenByNameAndAnyOtherNameStopsStartup(@TempDir final Path dir) throws Exception {
        final SearchBackendConfiguration config = new SearchBackendConfiguration();
        final SymmetricCipher cipher = new SymmetricCipher(SECRET);
        final DocumentSearchService embedded = config.documentSearchService(" Embedded ", dir.toString(),
                settingsService, cipher);
        assertInstanceOf(EmbeddedSearchService.class, embedded);
        assertEquals(dir.toAbsolutePath().normalize(), ((EmbeddedSearchService) embedded).indexDirectory());
        ((EmbeddedSearchService) embedded).close();
        assertInstanceOf(OpenSearchIndexService.class,
                config.documentSearchService("opensearch", dir.toString(), settingsService, cipher));

        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.documentSearchService("elastic", dir.toString(), settingsService, cipher));
        assertEquals("PHILTER_WORKSPACE_SEARCH_BACKEND must be \"embedded\" or \"opensearch\", not \"elastic\".",
                e.getMessage());
    }

    @Test
    void theIndexKeyIsDerivedAndIsNotTheFieldKey() {
        final SymmetricCipher cipher = new SymmetricCipher(SECRET);
        final byte[] indexKey = cipher.deriveKey(SearchBackendConfiguration.INDEX_KEY_PURPOSE).getEncoded();
        assertEquals(32, indexKey.length);
        assertTrue(!java.util.Arrays.equals(indexKey, java.util.Base64.getDecoder().decode(SECRET)));
        assertTrue(java.util.Arrays.equals(indexKey,
                cipher.deriveKey(SearchBackendConfiguration.INDEX_KEY_PURPOSE).getEncoded()), "stable");
    }

    @Test
    void aRebuildEmptiesTheIndexThenIndexesEveryDocumentWithText(@TempDir final Path dir) throws Exception {
        final GeneralSettings settings = new GeneralSettings();
        settings.setFullTextSearchEnabled(true);
        when(settingsService.load()).thenReturn(settings);
        final EmbeddedSearchService search = new EmbeddedSearchService(dir,
                new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"), settingsService);
        final DocumentRepository documents = mock(DocumentRepository.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final List<Document> all = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            final Document d = new Document();
            d.setId(String.format("d%03d", i));
            d.setBatchId("b1");
            d.setOriginalText(i % 10 == 0 ? null : "filing number " + i + " about leases");
            all.add(d);
        }
        when(documents.count()).thenReturn((long) all.size());
        when(documents.findAll(any(Pageable.class))).thenAnswer(inv -> {
            final Pageable p = inv.getArgument(0);
            final int from = (int) p.getOffset();
            return new PageImpl<>(all.subList(Math.min(from, all.size()), Math.min(from + p.getPageSize(), all.size())),
                    p, all.size());
        });
        search.indexDocument(stale());
        final SearchIndexRebuildService rebuild = new SearchIndexRebuildService(search, documents, settingsService, audit);

        for (int round = 0; round < 2; round++) {
            assertNull(rebuild.start("admin@example.com"));
            final long deadline = System.currentTimeMillis() + 30_000;
            while (rebuild.status().running() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            final SearchIndexRebuildService.Status s = rebuild.status();
            assertEquals(225, s.indexed());
            assertEquals(25, s.skipped());
            assertNull(s.error());
            assertEquals(225, search.search("leases", 0, 10).total(), "the same index each time");
            assertEquals(0, search.search("stale", 0, 10).total(), "a document no longer in the database is gone");
        }
        verify(audit, org.mockito.Mockito.times(2)).logForUser(eq("admin@example.com"),
                eq("SEARCH_INDEX_REBUILD_FINISH"), eq("Settings"), eq("search-index"),
                eq(AuditLogService.OUTCOME_SUCCESS),
                eq(Map.of("backend", "embedded", "indexed", 225L, "skipped", 25L)));
        search.close();
    }

    @Test
    void aRebuildIsRefusedWhileSearchIsOff() {
        when(settingsService.load()).thenReturn(new GeneralSettings());
        final SearchIndexRebuildService rebuild = new SearchIndexRebuildService(mock(DocumentSearchService.class),
                mock(DocumentRepository.class), settingsService, mock(AuditLogService.class));
        assertEquals("Full text search is off, so there is no index to rebuild.", rebuild.start("a"));
    }

    private static Document stale() {
        final Document d = new Document();
        d.setId("gone");
        d.setOriginalText("stale document");
        return d;
    }
}
