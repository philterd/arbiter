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
import ai.philterd.workspace.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rebuilds the full-text index from the database (#7): empties it, then indexes every document that still has its
 * original text, a page at a time, in the background. Running it again gives the same index, so it is safe to repeat;
 * one runs at a time. Used to fill a new embedded index, such as after moving from OpenSearch, and to recover one.
 */
@Service
public class SearchIndexRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexRebuildService.class);
    static final int PAGE = 100;

    private final DocumentSearchService search;
    private final DocumentRepository documents;
    private final GeneralSettingsService settings;
    private final AuditLogService audit;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Status status = new Status(false, 0, 0, 0, null, null, null);

    /** The latest rebuild: whether it is running, documents indexed and skipped, and how it ended. */
    public record Status(boolean running, long indexed, long skipped, long total, Instant startedAt,
                         Instant finishedAt, String error) {}

    public SearchIndexRebuildService(final DocumentSearchService search, final DocumentRepository documents,
                                     final GeneralSettingsService settings, final AuditLogService audit) {
        this.search = search;
        this.documents = documents;
        this.settings = settings;
        this.audit = audit;
    }

    public Status status() {
        return status;
    }

    /**
     * Starts a rebuild, unless one is running or full-text search is off.
     *
     * @return null when started, or why not
     */
    public String start(final String actor) {
        if (!settings.load().isFullTextSearchEnabled()) {
            return "Full text search is off, so there is no index to rebuild.";
        }
        if (!running.compareAndSet(false, true)) {
            return "A rebuild is already running.";
        }
        final long total = documents.count();
        status = new Status(true, 0, 0, total, Instant.now(), null, null);
        audit.log("SEARCH_INDEX_REBUILD_START", "Settings", "search-index",
                Map.of("backend", search.backend(), "documents", total));
        Thread.ofVirtual().name("search-index-rebuild").start(() -> run(actor, total));
        return null;
    }

    void run(final String actor, final long total) {
        long indexed = 0;
        long skipped = 0;
        String error = null;
        try {
            if (!search.clear()) {
                throw new IllegalStateException("The index could not be emptied.");
            }
            int page = 0;
            Page<Document> batch;
            do {
                batch = documents.findAll(PageRequest.of(page++, PAGE, Sort.by("id")));
                final List<Document> withText = new ArrayList<>();
                for (Document d : batch.getContent()) {
                    if (d.getOriginalText() == null || d.getOriginalText().isEmpty()) {
                        skipped++;
                    } else {
                        withText.add(d);
                    }
                }
                if (search instanceof EmbeddedSearchService embedded) {
                    embedded.indexDocuments(withText);
                } else {
                    withText.forEach(search::indexDocument);
                }
                indexed += withText.size();
                status = new Status(true, indexed, skipped, total, status.startedAt(), null, null);
            } while (batch.hasNext());
        } catch (Exception e) {
            error = "The rebuild stopped: " + e.getClass().getSimpleName() + ".";
            log.warn("Search index rebuild failed after {} documents: {}", indexed, e.getClass().getSimpleName());
        } finally {
            status = new Status(false, indexed, skipped, total, status.startedAt(), Instant.now(), error);
            running.set(false);
            audit.logForUser(actor, "SEARCH_INDEX_REBUILD_FINISH", "Settings", "search-index",
                    error == null ? AuditLogService.OUTCOME_SUCCESS : AuditLogService.OUTCOME_FAILURE,
                    Map.of("backend", search.backend(), "indexed", indexed, "skipped", skipped));
        }
    }
}
