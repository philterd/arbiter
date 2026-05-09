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

import ai.philterd.arbiter.model.GeneralSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceHostAllowListTest {

    // ---------- no allow-list configured: public hosts allowed, private always blocked ----------

    @Test
    void noAllowListPermitsPublicHostnames() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isEnforced());
        // Unresolvable hostnames are treated as public (can't be connected to anyway).
        assertTrue(list.isAllowed("http://opensearch.internal:9200"));
        assertTrue(list.isAllowed("https://anything.example.com/"));
    }

    @Test
    void nullConfigurationPermitsPublicHostnames() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList(null);
        assertFalse(list.isEnforced());
        assertTrue(list.isAllowed("http://anywhere"));
    }

    @Test
    void blankPatternsAreIgnored() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("  ,, ,");
        assertFalse(list.isEnforced(), "all-blank → effectively unset");
        assertTrue(list.isAllowed("http://anywhere"));
    }

    // ---------- private-range blocking (always enforced) ----------

    @Test
    void noAllowListBlocksLoopbackByName() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed("http://localhost:9200"));
    }

    @Test
    void noAllowListBlocksLoopbackByIpv4() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed("http://127.0.0.1:9200"));
        assertFalse(list.isAllowed("http://127.255.255.255/"));
    }

    @Test
    void noAllowListBlocksRfc1918Class10() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed("http://10.0.0.1/"));
        assertFalse(list.isAllowed("http://10.255.255.255:9200"));
    }

    @Test
    void noAllowListBlocksRfc1918Class172() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed("http://172.16.0.1/"));
        assertFalse(list.isAllowed("http://172.31.255.255/"));
    }

    @Test
    void noAllowListBlocksRfc1918Class192() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed("http://192.168.0.1/"));
        assertFalse(list.isAllowed("http://192.168.255.255:8080/"));
    }

    @Test
    void noAllowListBlocksLinkLocal() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        // Cloud metadata endpoint is the most sensitive link-local address.
        assertFalse(list.isAllowed("http://169.254.169.254/latest/meta-data/"));
        assertFalse(list.isAllowed("http://169.254.0.1/"));
    }

    @Test
    void noAllowListBlocksNullAndBlankUrls() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        assertFalse(list.isAllowed(null));
        assertFalse(list.isAllowed(""));
        assertFalse(list.isAllowed("   "));
    }

    @Test
    void noAllowListBlocksUnparseableUrls() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("");
        // URI.create throws for whitespace-containing strings → rejected.
        assertFalse(list.isAllowed("not a url"));
        assertFalse(list.isAllowed("/some/relative/path"));
    }

    // ---------- allowlist can explicitly permit a private address ----------

    @Test
    void allowListCanWhitelistLoopback() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("localhost");
        assertTrue(list.isAllowed("http://localhost:9200"));
    }

    @Test
    void allowListCanWhitelistPrivateIp() {
        final DataSourceHostAllowList list = new DataSourceHostAllowList("192.168.1.100");
        assertTrue(list.isAllowed("http://192.168.1.100:9200"));
        // Other private IPs still blocked.
        assertFalse(list.isAllowed("http://192.168.1.101:9200"));
        assertFalse(list.isAllowed("http://10.0.0.1/"));
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

    // ---------- master switch (Admin → Security) ----------

    @SuppressWarnings("unchecked")
    private static DataSourceHostAllowList listWithToggle(final String csv,
                                                          final boolean enabled) {
        final GeneralSettings settings = new GeneralSettings();
        settings.setHostAllowListEnabled(enabled);
        final GeneralSettingsService service = mock(GeneralSettingsService.class);
        when(service.load()).thenReturn(settings);
        final ObjectProvider<GeneralSettingsService> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(service);
        when(provider.getIfAvailable(any())).thenReturn(service);
        return new DataSourceHostAllowList(csv, provider);
    }

    @Test
    void masterSwitchOffPermitsHostThatPatternsWouldBlock() {
        // The patterns explicitly list only example.com; without the toggle off,
        // a.com would be rejected. With the toggle off, the same URL passes.
        final DataSourceHostAllowList list = listWithToggle("example.com", false);
        assertTrue(list.isAllowed("https://a.com:9200/_search"),
                "master switch off should allow public hosts that patterns reject");
    }

    @Test
    void masterSwitchOffPermitsPrivateRangeHost() {
        // The default-deny on RFC-1918 / loopback is itself part of the allow-list
        // — when the master switch is off, private addresses also pass. This is
        // the SSRF re-opening the docs warn about, so it has to be explicit.
        final DataSourceHostAllowList list = listWithToggle("", false);
        assertTrue(list.isAllowed("http://127.0.0.1:9200"),
                "master switch off should bypass the private-range default-deny");
        assertTrue(list.isAllowed("http://10.0.0.5:9200"));
        assertTrue(list.isAllowed("http://192.168.1.10:9200"));
    }

    @Test
    void masterSwitchOnRestoresOriginalEnforcement() {
        // Sanity: with the toggle on, the same constructor produces the same
        // allow/deny decisions as the test-only constructor with the same CSV.
        final DataSourceHostAllowList list = listWithToggle("example.com", true);
        assertTrue(list.isAllowed("https://example.com:9200"));
        assertFalse(list.isAllowed("https://other.com:9200"));
        assertFalse(list.isAllowed("http://127.0.0.1:9200"),
                "private-range default-deny must still apply when the master switch is on");
    }

    @Test
    void masterSwitchOffEvenForBlankUrlReturnsFalse() {
        // The toggle relaxes the pattern checks but doesn't accept malformed
        // input — a blank/null URL still rejects, since there's no host to
        // even attempt a connection against.
        final DataSourceHostAllowList list = listWithToggle("example.com", false);
        assertFalse(list.isAllowed(null));
        assertFalse(list.isAllowed(""));
        assertFalse(list.isAllowed("   "));
    }
}
