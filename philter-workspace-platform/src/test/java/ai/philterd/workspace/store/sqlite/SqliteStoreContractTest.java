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

import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.store.BackgroundJobStore;
import ai.philterd.workspace.store.DocumentQueueStore;
import ai.philterd.workspace.store.StoreContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The shared store behaviour, on a SQLite database file. */
class SqliteStoreContractTest extends StoreContract {

    @TempDir
    Path dir;

    private SqliteTestStore db;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Override
    protected <R> R repository(final Class<R> type) {
        return db.repository(type);
    }

    @Override
    protected DocumentLockService locks() {
        return new SqliteDocumentLockService(db.store);
    }

    @Override
    protected DocumentQueueStore queue() {
        return new SqliteDocumentQueueStore(db.store);
    }

    @Override
    protected BackgroundJobStore jobs() {
        return new SqliteBackgroundJobStore(db.store);
    }

    @Override
    protected AuditLogQueryService audit() {
        return new SqliteAuditLogQueryService(db.store);
    }

    @Override
    protected <T> T save(final T entity) {
        return db.store.save(entity);
    }

    @Override
    protected <T> Optional<T> findById(final Class<T> type, final String id) {
        return db.store.findById(type, id);
    }

    @Override
    protected String raw(final Class<?> type, final String id) {
        return db.rawDoc(db.store.info(type).table, id);
    }

    @Override
    protected void setField(final Class<?> type, final String id, final String field, final Object value) {
        db.store.updateOne(type, Where.eq("id", id), Map.of(field, value), List.of());
    }

    @Override
    protected void removeField(final Class<?> type, final String id, final String field) {
        db.jdbc.update("UPDATE \"" + db.store.info(type).table + "\" SET doc = json_remove(doc, '$." + field
                + "') WHERE id = ?", id);
    }
}
