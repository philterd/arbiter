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

import java.util.Collection;
import java.util.List;

/**
 * The full-text index over Philter Workspace's documents (#7): the Search page, {@code GET /api/v1/search}, and Find
 * similar documents. One backend is in use, chosen by {@code philter-workspace.search.backend}: the embedded Lucene
 * index ({@code embedded}, the default) or an OpenSearch cluster ({@code opensearch}).
 *
 * <p>Every method is a no-op returning an empty or neutral result while full-text search is turned off in
 * {@link ai.philterd.workspace.model.GeneralSettings}, and none throws: search is discovery, so a failing backend must
 * not break ingest or review.
 */
public interface DocumentSearchService {

    record SearchHit(String id, String batchId, String filename, String status, List<String> highlights) {}

    record SearchResults(long total, int from, int size, List<SearchHit> hits) {}

    /** Whether the backend can be used, for the health endpoint. */
    enum State {
        /** Full-text search is off, so nothing is checked. */
        DISABLED,
        /** The backend answered. */
        REACHABLE,
        /** The backend did not answer, or failed. */
        UNREACHABLE
    }

    /** {@code embedded} or {@code opensearch}, as {@code philter-workspace.search.backend} names it. */
    String backend();

    /** Indexes, or replaces, the document's original text. Best effort. */
    void indexDocument(Document document);

    /**
     * Removes a document from the index, for a document deleted or whose text was cleared. A document that was never
     * indexed is not an error.
     *
     * @return whether the index no longer holds the document (true also when search is off)
     */
    boolean removeDocument(String documentId);

    /** A full-text match over every document; for callers who may see every batch. */
    default SearchResults search(final String query, final int from, final int size) {
        return search(query, from, size, null);
    }

    /**
     * A full-text match restricted to {@code allowedBatchIds} when not null, so neither the total nor the hits can
     * disclose documents the caller may not see. An empty allow-list returns nothing. Highlights are safe HTML: the
     * document text escaped, with matched terms in {@code <mark>}.
     */
    SearchResults search(String query, int from, int size, Collection<String> allowedBatchIds);

    /** Documents in the same batch (when given) whose text resembles the document's, excluding the document. */
    SearchResults findSimilar(String documentId, String batchId, int size);

    /** Removes every document from the index, before a rebuild. Returns whether it succeeded. */
    boolean clear();

    /** Whether the backend can be used, for the health endpoint. Sends no document data. */
    State probe();
}
