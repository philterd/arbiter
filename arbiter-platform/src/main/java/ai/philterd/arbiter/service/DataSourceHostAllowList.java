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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Host allow-list for data-source URLs (OpenSearch / Elasticsearch test endpoints,
 * ingest workers, Philter, and Ollama test-connection calls).
 *
 * <p><strong>Default-deny for private ranges.</strong> RFC-1918, loopback, and link-local
 * addresses (10.x, 172.16–31.x, 192.168.x, 127.x, 169.254.x, ::1, fe80::) are blocked
 * by default — when no allow-list is configured, every private-range host is refused.
 * This closes the admin-triggered SSRF path against internal infrastructure (cloud
 * metadata endpoints, MongoDB / Redis on loopback, intranet HTTP services on RFC-1918,
 * etc.) without requiring any operator configuration. To admit a private host the
 * operator genuinely needs to reach (a legitimate internal OpenSearch, for example),
 * list it explicitly in the allow-list:
 *
 * <pre>
 *   arbiter.data-sources.allowed-hosts=opensearch.internal,192.168.1.100
 * </pre>
 *
 * <p>That admits both {@code opensearch.internal} and the private literal
 * {@code 192.168.1.100}. The default-deny is therefore not absolute — patterns override
 * it for the hosts they name.
 *
 * <p><strong>Allow-list behavior.</strong> The format is a comma-separated list of host
 * patterns. Each pattern is either:
 *
 * <ul>
 *   <li>An <strong>exact hostname</strong> like {@code opensearch.internal}, or
 *   <li>A <strong>leading-wildcard</strong> like {@code *.search.example.com},
 *       which matches one-or-more left-side labels (so {@code a.search.example.com}
 *       and {@code search.example.com} both match, but {@code search.example.org} does not).
 * </ul>
 *
 * <p>Combined behavior:
 *
 * <ul>
 *   <li>Allow-list unset, public host → accepted.</li>
 *   <li>Allow-list unset, private-range host → rejected.</li>
 *   <li>Allow-list set, host matches a pattern (public or private) → accepted.</li>
 *   <li>Allow-list set, host doesn't match → rejected.</li>
 * </ul>
 *
 * <p><strong>Master switch.</strong> An admin can disable the allow-list entirely from
 * Admin → Security ({@link GeneralSettings#isHostAllowListEnabled()}). When disabled,
 * {@link #isAllowed(String)} returns {@code true} for every well-formed URL — the
 * private-range and pattern checks are skipped. This is documented as not recommended
 * because it re-opens the SSRF surface those checks are designed to close.
 */
@Component
public class DataSourceHostAllowList {

    private static final Logger log = LoggerFactory.getLogger(DataSourceHostAllowList.class);

    private final List<String> patterns;
    /**
     * Lazy reference to the settings service so a runtime toggle of the allow-list
     * is honored on the next {@link #isAllowed(String)} call without restarting.
     * {@code ObjectProvider} avoids the eager-construction cycle Spring would
     * otherwise build between this component and {@code GeneralSettingsService}.
     */
    private final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider;

    @Autowired
    public DataSourceHostAllowList(
            @Value("${arbiter.data-sources.allowed-hosts:}") final String csv,
            final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider) {
        this(csv, generalSettingsServiceProvider, true);
    }

    /**
     * Test-only constructor that takes just the CSV. Skips the runtime toggle
     * lookup (the allow-list always behaves as-if-enabled), which is the right
     * shape for the existing unit tests that exercise pattern matching and
     * private-range rules in isolation.
     */
    public DataSourceHostAllowList(final String csv) {
        this(csv, null, false);
    }

    private DataSourceHostAllowList(final String csv,
                                    final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider,
                                    @SuppressWarnings("unused") final boolean unusedDistinguisher) {
        this.generalSettingsServiceProvider = generalSettingsServiceProvider;
        if (csv == null || csv.isBlank()) {
            this.patterns = List.of();
        } else {
            this.patterns = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toList();
            if (!patterns.isEmpty()) {
                log.info("Data-source host allow-list active with {} pattern(s): {}",
                        patterns.size(), patterns);
            }
        }
    }

    /** {@code true} if the allow-list is configured (i.e. a non-empty pattern list was supplied). */
    public boolean isEnforced() {
        return !patterns.isEmpty();
    }

    /**
     * Returns {@code true} if the URL's host is permitted.
     *
     * <p>Private/loopback/link-local addresses are always blocked unless the host is
     * explicitly listed in the configured allow-list. Public addresses are blocked only
     * when an allow-list is configured and the host doesn't match any pattern.
     *
     * <p>A {@code null}, blank, or unparseable URL (one without a recognisable host
     * component) is always rejected.
     */
    public boolean isAllowed(final String url) {
        if (url == null || url.isBlank()) return false;
        final String host;
        try {
            final URI uri = URI.create(url.trim());
            host = uri.getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isBlank()) return false;
        final String h = host.toLowerCase(Locale.ROOT);

        // Master switch: when an admin has turned the allow-list off via
        // Admin → Security, accept any well-formed host regardless of the
        // configured patterns or the private-range default-deny. This is the
        // documented not-recommended escape hatch for deployments that need to
        // reach hosts the operator can't easily enumerate up front.
        if (!isEnabledByAdmin()) {
            return true;
        }

        // Private/loopback/link-local: blocked unconditionally unless explicitly whitelisted.
        if (isPrivateAddress(host)) {
            return !patterns.isEmpty() && matchesPatterns(h);
        }

        // Public addresses: allowed when no allow-list is configured; otherwise must match.
        return patterns.isEmpty() || matchesPatterns(h);
    }

    /**
     * Reads the admin toggle from settings. Defaults to {@code true} when no
     * settings row exists yet (fresh install) or the service isn't available
     * (test contexts that wire only this component); the safest default for an
     * SSRF defence is "on".
     */
    private boolean isEnabledByAdmin() {
        final GeneralSettingsService svc = generalSettingsServiceProvider == null
                ? null : generalSettingsServiceProvider.getIfAvailable();
        if (svc == null) return true;
        try {
            return svc.load().isHostAllowListEnabled();
        } catch (RuntimeException e) {
            // If the settings store is briefly unreachable, fall back to the
            // safe default rather than leaving the allow-list unenforced.
            log.debug("Could not read host-allow-list toggle, defaulting to enabled: {}",
                    e.getMessage());
            return true;
        }
    }

    /** Useful in error messages so admins know what to add to their allow-list. */
    public List<String> patterns() {
        return patterns;
    }

    /**
     * Returns {@code true} if the host resolves to a loopback, site-local (RFC-1918), or
     * link-local address. Numeric IP literals are parsed without a DNS lookup; hostnames
     * are resolved via the JVM. If the hostname cannot be resolved, {@code false} is
     * returned — an unresolvable host cannot be connected to anyway.
     */
    private static boolean isPrivateAddress(final String host) {
        try {
            final InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private boolean matchesPatterns(final String h) {
        for (String spec : patterns) {
            if (spec.startsWith("*.")) {
                // *.foo.com matches anything ending in .foo.com plus the bare apex foo.com.
                final String suffix = spec.substring(1); // ".foo.com"
                final String apex = spec.substring(2);   // "foo.com"
                if (h.endsWith(suffix) || h.equals(apex)) return true;
            } else if (spec.equals(h)) {
                return true;
            }
        }
        return false;
    }
}
