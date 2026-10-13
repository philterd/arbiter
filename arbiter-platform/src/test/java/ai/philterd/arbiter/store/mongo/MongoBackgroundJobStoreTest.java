/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.store.mongo;

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoBackgroundJobStoreTest {

    @Test
    void claimRefusedByUniqueIndexReturnsNull() {
        final MongoOperations mongo = mock(MongoOperations.class);
        when(mongo.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        assertNull(new MongoBackgroundJobStore(mongo).claimPending("j1"));
    }

    @Test
    void removeAllWithStatusReturnsOnlyDeletedIds() {
        final MongoOperations mongo = mock(MongoOperations.class);
        final Document a = new Document();
        a.setId("a");
        final Document b = new Document();
        b.setId("b");
        when(mongo.find(any(Query.class), eq(Document.class))).thenReturn(List.of(a, b));
        // "b" was re-processed between the read and its delete.
        when(mongo.findAndRemove(any(Query.class), eq(Document.class))).thenReturn(a, (Document) null);

        assertEquals(List.of("a"), new MongoDocumentQueueStore(mongo).removeAllWithStatus("FAILED"));
    }
}
