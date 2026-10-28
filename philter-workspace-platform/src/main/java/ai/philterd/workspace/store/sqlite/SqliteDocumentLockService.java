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
import ai.philterd.workspace.service.DocumentLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Review locks taken and released by single atomic UPDATE statements. */
@Service
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "sqlite")
public class SqliteDocumentLockService implements DocumentLockService {

    private static final List<String> LOCK_FIELDS = List.of("lockedBy", "lockedAt", "lockExpiresAt");

    private final SqliteDocumentStore store;

    public SqliteDocumentLockService(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    public Document acquire(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return null;
        final Instant now = Instant.now();
        final Where notHeldByOther = Where.or(List.of(
                Where.eq("lockedBy", null),
                Where.eq("lockExpiresAt", null),
                Where.cmp("lockExpiresAt", "<=", now),
                Where.eq("lockedBy", email)));
        return store.updateOne(Document.class, Where.and(Where.eq("id", documentId), notHeldByOther),
                Map.of("lockedBy", email, "lockExpiresAt", now.plus(LOCK_TTL), "lockedAt", now), List.of());
    }

    @Override
    public Document pulse(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return null;
        return store.updateOne(Document.class, Where.and(Where.eq("id", documentId), Where.eq("lockedBy", email)),
                Map.of("lockExpiresAt", Instant.now().plus(LOCK_TTL)), List.of());
    }

    @Override
    public void release(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return;
        store.updateOne(Document.class, Where.and(Where.eq("id", documentId), Where.eq("lockedBy", email)),
                Map.of(), LOCK_FIELDS);
    }

    @Override
    public Document breakLock(final String documentId) {
        if (documentId == null) return null;
        return store.updateOne(Document.class, Where.eq("id", documentId), Map.of(), LOCK_FIELDS);
    }
}
