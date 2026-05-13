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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The admin-flippable host allow-list toggle is meant to be a "loose mode" for
 * <em>public</em> hosts — an escape hatch for deployments that need to reach hosts
 * the operator can't enumerate up front. The previous shape made it a full kill
 * switch that, when off, also waved through loopback, link-local, RFC-1918, and
 * cloud-metadata addresses — silently re-opening the SSRF path the allow-list
 * was built to close.
 *
 * <p>These tests pin the corrected contract: the toggle only widens acceptance for
 * public hosts. The private-range backstop remains in force regardless of the
 * toggle state, and is only overridden by an explicit allow-list entry.
 */
class DataSourceHostAllowListToggleTest {

    private static ObjectProvider<GeneralSettingsService> providerWithToggle(final boolean enabled) {
        final GeneralSettings settings = new GeneralSettings();
        settings.setHostAllowListEnabled(enabled);
        final GeneralSettingsService svc = mock(GeneralSettingsService.class);
        when(svc.load()).thenReturn(settings);

        @SuppressWarnings("unchecked")
        final ObjectProvider<GeneralSettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(svc);
        // ObjectProvider has more methods than just getIfAvailable — keep them stubbed
        // permissively so Mockito doesn't complain if internals reach for them.
        lenient().when(provider.getObject()).thenReturn(svc);
        lenient().when(provider.getIfUnique()).thenReturn(svc);
        return provider;
    }

    private static DataSourceHostAllowList listWith(final String csv, final boolean toggleEnabled) {
        return new DataSourceHostAllowList(csv, providerWithToggle(toggleEnabled));
    }

    // ===================================================================
    // Toggle ON (the default) — behaviour is the same as before the fix.
    // ===================================================================

    @Test
    void toggleOnWithoutAllowListAllowsPublicHosts() {
        final DataSourceHostAllowList list = listWith("", true);
        assertTrue(list.isAllowed("https://opensearch.example.com:9200/"));
        assertTrue(list.isAllowed("http://198.51.100.10:9200/")); // TEST-NET-2, treated as public
    }

    @Test
    void toggleOnWithoutAllowListBlocksAllPrivateRanges() {
        // Default-deny applies to the canonical private-range hits.
        final DataSourceHostAllowList list = listWith("", true);
        assertFalse(list.isAllowed("http://127.0.0.1:9200/"),            "loopback");
        assertFalse(list.isAllowed("http://10.0.0.5:9200/"),             "RFC-1918 10/8");
        assertFalse(list.isAllowed("http://192.168.1.50:9200/"),         "RFC-1918 192.168/16");
        assertFalse(list.isAllowed("http://169.254.169.254/latest/meta-data/"), "cloud metadata");
        assertFalse(list.isAllowed("http://[::1]:9200/"),                "IPv6 loopback");
        assertFalse(list.isAllowed("http://0.0.0.0:9200/"),              "any-local IPv4");
        assertFalse(list.isAllowed("http://[fc00::1]:9200/"),            "IPv6 ULA");
    }

    @Test
    void toggleOnWithExplicitWhitelistAdmitsThatPrivateHost() {
        // The default-deny is overridable per-host. A deployer who genuinely needs to
        // reach an internal OpenSearch lists it explicitly and that host alone is admitted.
        final DataSourceHostAllowList list = listWith("192.168.1.100", true);
        assertTrue(list.isAllowed("http://192.168.1.100:9200/"));
        assertFalse(list.isAllowed("http://192.168.1.101:9200/"),
                "neighbouring private IP not on the list must still be denied");
    }

    // ===================================================================
    // Toggle OFF — the public-only escape hatch.
    // ===================================================================

    @Test
    void toggleOffStillBlocksLoopback() {
        // The primary regression: the toggle used to wave loopback through. It must not.
        final DataSourceHostAllowList list = listWith("", false);
        assertFalse(list.isAllowed("http://127.0.0.1:9200/"));
        assertFalse(list.isAllowed("http://127.0.0.1:27017/"));   // Mongo on loopback
        assertFalse(list.isAllowed("http://[::1]:6379/"));         // Valkey on IPv6 loopback
    }

    @Test
    void toggleOffStillBlocksCloudMetadataLiteral() {
        // The classic SSRF-via-data-source target. Without this backstop, an admin who
        // flipped the toggle for any reason hands a hijacked admin session a path to
        // IAM credentials on every major cloud.
        final DataSourceHostAllowList list = listWith("", false);
        assertFalse(list.isAllowed("http://169.254.169.254/latest/meta-data/"));
        assertFalse(list.isAllowed("http://[fe80::1]/"),
                "IPv6 link-local must also be refused under toggle-off");
    }

    @Test
    void toggleOffStillBlocksRfc1918() {
        final DataSourceHostAllowList list = listWith("", false);
        assertFalse(list.isAllowed("http://10.0.0.5:9200/"));
        assertFalse(list.isAllowed("http://172.16.0.5:9200/"));
        assertFalse(list.isAllowed("http://172.31.255.255:9200/"));
        assertFalse(list.isAllowed("http://192.168.0.5:9200/"));
    }

    @Test
    void toggleOffStillBlocksAnyLocal() {
        // 0.0.0.0 collapses to "any local interface" in many HTTP clients, which means
        // it functionally aliases loopback. Refuse it like loopback regardless of the toggle.
        final DataSourceHostAllowList list = listWith("", false);
        assertFalse(list.isAllowed("http://0.0.0.0:9200/"));
    }

    @Test
    void toggleOffStillBlocksIpv6UniqueLocalAddresses() {
        // Java's InetAddress predates RFC 4193 — isSiteLocalAddress() returns false for
        // fc00::/7. We check the leading byte explicitly, and toggle state must not
        // override that.
        final DataSourceHostAllowList list = listWith("", false);
        assertFalse(list.isAllowed("http://[fc00::1]:9200/"));
        assertFalse(list.isAllowed("http://[fd12:3456:789a::1]:9200/"));
    }

    @Test
    void toggleOffAllowsPublicHostsThatDoNotMatchPatterns() {
        // This is the toggle's *intended* effect: a public host that wouldn't pass the
        // pattern check when the toggle is on is now waved through. The pattern set is
        // configured but should be effectively ignored for public hosts.
        final DataSourceHostAllowList list = listWith("opensearch.internal", false);
        assertTrue(list.isAllowed("https://elastic.example.com:9200/"));
        assertTrue(list.isAllowed("https://opensearch.partner.example.org/"));
    }

    @Test
    void toggleOffStillRespectsExplicitWhitelistForPrivateHost() {
        // Belt-and-braces: even with the toggle off, an admin's explicit private-host
        // whitelist remains the *only* way to admit a private host. A whitelisted IP
        // gets in; a neighbour does not.
        final DataSourceHostAllowList list = listWith("192.168.1.100", false);
        assertTrue(list.isAllowed("http://192.168.1.100:9200/"));
        assertFalse(list.isAllowed("http://192.168.1.101:9200/"));
        assertFalse(list.isAllowed("http://127.0.0.1:9200/"),
                "loopback not on list must still be denied even with toggle off");
    }

    // ===================================================================
    // Toggle indeterminate — settings service unavailable.
    // ===================================================================

    @Test
    void missingSettingsServiceDefaultsToToggleEnabled() {
        // The provider's getIfAvailable() returning null simulates a transient state
        // where the GeneralSettingsService bean hasn't been wired yet (a startup race).
        // The safe default for an SSRF defence is "on" so we don't briefly behave like
        // the toggle had been flipped off.
        @SuppressWarnings("unchecked")
        final ObjectProvider<GeneralSettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        final DataSourceHostAllowList list = new DataSourceHostAllowList("", provider);
        // Behaves like toggle-on: private-range default-deny applies.
        assertFalse(list.isAllowed("http://127.0.0.1:9200/"));
        // And public hosts still pass when no allow-list is configured.
        assertTrue(list.isAllowed("https://opensearch.example.com/"));
    }

    @Test
    void settingsServiceThrowingDefaultsToToggleEnabled() {
        // Another safe-default check: if GeneralSettingsService.load() blows up
        // (mongo briefly unreachable, etc.), the allow-list must keep enforcing.
        final GeneralSettingsService svc = mock(GeneralSettingsService.class);
        when(svc.load()).thenThrow(new RuntimeException("mongo timeout"));
        @SuppressWarnings("unchecked")
        final ObjectProvider<GeneralSettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(svc);

        final DataSourceHostAllowList list = new DataSourceHostAllowList("", provider);
        assertFalse(list.isAllowed("http://127.0.0.1:9200/"),
                "loopback must remain blocked if the toggle state is unreadable");
    }
}
