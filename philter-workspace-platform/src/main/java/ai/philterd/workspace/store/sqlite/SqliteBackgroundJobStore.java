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

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.store.BackgroundJobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "sqlite")
public class SqliteBackgroundJobStore implements BackgroundJobStore {

    private final SqliteDocumentStore store;

    public SqliteBackgroundJobStore(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    public long countRunning(final Collection<String> types) {
        return store.count(BackgroundJob.class,
                Where.and(Where.eq("status", BackgroundJob.STATUS_RUNNING), Where.in("type", types)));
    }

    @Override
    public BackgroundJob claimPending(final String jobId) {
        try {
            return store.updateOne(BackgroundJob.class,
                    Where.and(Where.eq("id", jobId), Where.eq("status", BackgroundJob.STATUS_PENDING)),
                    Map.of("status", BackgroundJob.STATUS_RUNNING, "startedAt", Instant.now()), List.of());
        } catch (DuplicateKeyException e) {
            // The partial unique index allows one RUNNING data import per batch.
            return null;
        }
    }
}
