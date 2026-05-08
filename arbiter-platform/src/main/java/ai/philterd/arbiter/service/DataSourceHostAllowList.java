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

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Optional defense-in-depth allow-list for data-source URLs (OpenSearch / Elasticsearch
 * test endpoints and the ingest workers). Admins are trusted, but if the application
 * runs in a network where Arbiter has access to internal services that the admin role
 * shouldn't be able to reach, the deployer can pin acceptable hosts via:
 *
 * <pre>
 *   arbiter.data-sources.allowed-hosts=opensearch.internal,*.search.example.com
 * </pre>
 *
 * <p>The format is a comma-separated list of host patterns. Each pattern is either:
 *
 * <ul>
 *   <li>An <strong>exact hostname</strong> like {@code opensearch.internal}, or
 *   <li>A <strong>leading-wildcard pattern</strong> like {@code *.search.example.com},
 *       which matches one-or-more left-side labels (so {@code a.search.example.com}
 *       and {@code search.example.com} both match, but {@code search.example.org}
 *       does not).
 * </ul>
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>If the property is unset or blank, the allow-list is <em>disabled</em> and any
 *       host is accepted — this preserves the long-standing default and means the
 *       feature is opt-in for security-sensitive deployments.
 *   <li>When configured, any URL whose host doesn't match a pattern is rejected. This
 *       applies to both admin "Test connection" buttons and to the saved data-source
 *       ingest workers, so a saved data source that points at a now-forbidden host
 *       fails fast when its job runs.
 * </ul>
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

    /** {@code true} if the allow-list is configured (i.e. enforced). */
    public boolean isEnforced() {
        return !patterns.isEmpty();
    }

    /**
     * Verify a URL's host is permitted by the allow-list. When the list is empty (default),
     * any host is allowed; otherwise the URL is parsed and its host compared to every
     * configured pattern.
     *
     * <p>A {@code null} or unparseable URL, or one without a host (e.g. {@code "not a url"}),
     * is rejected when the allow-list is configured.
     */
    public boolean isAllowed(final String url) {
        if (patterns.isEmpty()) return true;
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

    /** Useful in error messages so admins know what to add to their allow-list. */
    public List<String> patterns() {
        return patterns;
    }
}
