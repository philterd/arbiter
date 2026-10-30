/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.store.DocumentQueueStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "sqlite")
public class SqliteDocumentQueueStore implements DocumentQueueStore {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_FAILED = "FAILED";

    private final SqliteDocumentStore store;

    public SqliteDocumentQueueStore(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    public Document claimPending(final String documentId) {
        return store.updateOne(Document.class,
                Where.and(Where.eq("id", documentId), Where.eq("status", STATUS_PENDING)),
                Map.of("status", STATUS_PROCESSING, "statusChangedAt", LocalDateTime.now()), List.of());
    }

    @Override
    public Document requeueFailed(final String documentId) {
        return store.updateOne(Document.class,
                Where.and(Where.eq("id", documentId), Where.eq("status", STATUS_FAILED)),
                Map.of("status", STATUS_PENDING, "statusChangedAt", LocalDateTime.now()), List.of());
    }

    @Override
    public long requeueProcessingBefore(final LocalDateTime cutoff) {
        return store.updateAll(Document.class,
                Where.and(Where.eq("status", STATUS_PROCESSING), Where.cmp("statusChangedAt", "<", cutoff)),
                Map.of("status", STATUS_PENDING, "statusChangedAt", LocalDateTime.now()), List.of());
    }

    @Override
    public long countCreatedBetween(final LocalDateTime start, final LocalDateTime end) {
        return store.count(Document.class,
                Where.and(Where.cmp("createdAt", ">", start), Where.cmp("createdAt", "<=", end)));
    }

    @Override
    public long countStatusChangedBetween(final LocalDateTime start, final LocalDateTime end,
                                          final Collection<String> excludedStatuses) {
        return store.count(Document.class, Where.and(
                Where.cmp("statusChangedAt", ">", start),
                Where.cmp("statusChangedAt", "<=", end),
                Where.notIn("status", excludedStatuses)));
    }

    @Override
    public Document removeIfStatus(final String documentId, final String status) {
        final List<Document> removed = store.deleteReturning(Document.class,
                Where.and(Where.eq("id", documentId), Where.eq("status", status)));
        return removed.isEmpty() ? null : removed.get(0);
    }

    @Override
    public List<String> removeAllWithStatus(final String status) {
        final List<String> ids = new ArrayList<>();
        for (Document d : store.deleteReturning(Document.class, Where.eq("status", status))) {
            ids.add(d.getId());
        }
        return ids;
    }
}
