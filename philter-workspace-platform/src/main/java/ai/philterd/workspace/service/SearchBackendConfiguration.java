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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Chooses the full-text search backend (#7) from {@code philter-workspace.search.backend}
 * ({@code PHILTER_WORKSPACE_SEARCH_BACKEND}): {@code embedded} (the default) or {@code opensearch}. Any other value
 * stops Philter Workspace at startup rather than at its first search.
 */
@Configuration
public class SearchBackendConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SearchBackendConfiguration.class);

    public static final String EMBEDDED = "embedded";
    public static final String OPENSEARCH = "opensearch";
    /** The purpose the index key is derived for, so it is never the field-encryption key. */
    static final String INDEX_KEY_PURPOSE = "philter-workspace-search-index-v1";

    @Bean
    public DocumentSearchService documentSearchService(
            @Value("${philter-workspace.search.backend:embedded}") final String backend,
            @Value("${philter-workspace.search.index-dir:data/search-index}") final String indexDirectory,
            final GeneralSettingsService settings,
            final SymmetricCipher cipher) {
        final String chosen = backend == null ? "" : backend.trim().toLowerCase(Locale.ROOT);
        return switch (chosen) {
            case EMBEDDED -> {
                final Path dir = Path.of(indexDirectory).toAbsolutePath().normalize();
                log.info("Full-text search backend: embedded, index at {}", dir);
                yield new EmbeddedSearchService(dir, cipher.deriveKey(INDEX_KEY_PURPOSE), settings);
            }
            case OPENSEARCH -> {
                log.info("Full-text search backend: OpenSearch, configured under Admin > General");
                yield new OpenSearchIndexService(settings);
            }
            default -> throw new IllegalStateException("PHILTER_WORKSPACE_SEARCH_BACKEND must be \"embedded\" or "
                    + "\"opensearch\", not \"" + backend + "\".");
        };
    }
}
