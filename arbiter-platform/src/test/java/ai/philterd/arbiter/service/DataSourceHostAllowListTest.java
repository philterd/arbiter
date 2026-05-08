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

class DataSourceHostAllowListTest {

    // ---------- disabled by default ----------

    @Test
    void emptyConfigurationAllowsEverything() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isEnforced());
        assertTrue(list.isAllowed("http://opensearch.internal:9200"));
        assertTrue(list.isAllowed("https://anything.example.com/"));
        // Even a malformed URL passes when the list isn't enforced — the historical default.
        assertTrue(list.isAllowed("not a url"));
    }

    @Test
    void nullConfigurationAllowsEverything() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList(null);
        assertFalse(list.isEnforced());
        assertTrue(list.isAllowed("http://anywhere"));
    }

    @Test
    void blankPatternsAreIgnored() {
        // Whitespace-only entries are stripped during parsing.
        final DataSourceHostAllowList list = new DataSourceHostAllowList("  ,, ,");
        assertFalse(list.isEnforced(), "all-blank → effectively unset");
        assertTrue(list.isAllowed("http://anywhere"));
    }

    // ---------- enforced: exact match ----------

    @Test
    void exactHostnameMatches() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("opensearch.internal");
        assertTrue(list.isEnforced());
        assertTrue(list.isAllowed("http://opensearch.internal:9200/idx/_search"));
        // Different host — rejected.
        assertFalse(list.isAllowed("http://example.com:9200/"));
        // Different port doesn't matter; only the host is checked.
        assertTrue(list.isAllowed("http://opensearch.internal:9300"));
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("OpenSearch.INTERNAL");
        assertTrue(list.isAllowed("http://opensearch.internal:9200"));
        assertTrue(list.isAllowed("http://OPENSEARCH.INTERNAL:9200"));
    }

    // ---------- enforced: leading wildcard ----------

    @Test
    void wildcardMatchesSubdomainsAndApex() {
        final DataSourceHostAllowList list =
                new DataSourceHostAllowList("*.search.example.com");
        // Subdomain.
        assertTrue(list.isAllowed("https://a.search.example.com/"));
        // Multi-level subdomain.
        assertTrue(list.isAllowed("https://a.b.search.example.com/"));
        // Apex (the bare `search.example.com`).
        assertTrue(list.isAllowed("https://search.example.com/"));
        // Different parent — rejected.
        assertFalse(list.isAllowed("https://search.example.org/"));
        // Bare suffix match without the dot is rejected.
        assertFalse(list.isAllowed("https://malicioussearch.example.com/"));
    }

    @Test
    void multiplePatternsAreAllChecked() {
        final DataSourceHostAllowList list =
                new DataSourceHostAllowList("opensearch.internal, *.search.example.com");
        assertTrue(list.isAllowed("http://opensearch.internal/"));
        assertTrue(list.isAllowed("https://a.search.example.com/"));
        assertFalse(list.isAllowed("https://other.example.com/"));
    }

    // ---------- enforced: invalid input ----------

    @Test
    void blankUrlsRejectedWhenEnforced() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("opensearch.internal");
        assertFalse(list.isAllowed(null));
        assertFalse(list.isAllowed(""));
        assertFalse(list.isAllowed("   "));
    }

    @Test
    void unparseableUrlsRejectedWhenEnforced() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("opensearch.internal");
        // No host component — rejected even though the path has a matching string in it.
        assertFalse(list.isAllowed("not a url with opensearch.internal in it"));
        // Relative-only URL — no host, rejected.
        assertFalse(list.isAllowed("/some/path"));
    }

    // ---------- patterns() exposes what was parsed ----------

    @Test
    void patternsReportConfiguredEntries() {
        final DataSourceHostAllowList list =
                new DataSourceHostAllowList("a.example.com, *.b.example.com");
        assertEquals(2, list.patterns().size());
        assertTrue(list.patterns().contains("a.example.com"));
        assertTrue(list.patterns().contains("*.b.example.com"));
    }
}
