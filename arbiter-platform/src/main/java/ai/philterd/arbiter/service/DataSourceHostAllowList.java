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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * addresses (10.x, 172.16–31.x, 192.168.x, 127.x, 169.254.x, ::1, fe80::) are
 * <em>always</em> blocked regardless of whether an allow-list is configured. This prevents
 * admin-triggered SSRF probing of internal infrastructure. To allow a private address
 * (e.g., a legitimate internal OpenSearch), add it explicitly to the allow-list:
 *
 * <pre>
 *   arbiter.data-sources.allowed-hosts=opensearch.internal,192.168.1.100
 * </pre>
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
 * <p>When the allow-list is unset, any <em>public</em> host is accepted. When it is set,
 * only hosts that match a configured pattern are accepted (and private-range addresses must
 * also match to pass the unconditional block above).
 */
@Component
public class DataSourceHostAllowList {

    private static final Logger log = LoggerFactory.getLogger(DataSourceHostAllowList.class);

    private final List<String> patterns;

    public DataSourceHostAllowList(
            @Value("${arbiter.data-sources.allowed-hosts:}") final String csv) {
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

        // Private/loopback/link-local: blocked unconditionally unless explicitly whitelisted.
        if (isPrivateAddress(host)) {
            return !patterns.isEmpty() && matchesPatterns(h);
        }

        // Public addresses: allowed when no allow-list is configured; otherwise must match.
        return patterns.isEmpty() || matchesPatterns(h);
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
