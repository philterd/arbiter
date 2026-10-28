/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.mongo;

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.Document;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void s3ClaimRefusedByUniqueIndexStaysPending() {
        // MongoDB rejects the RUNNING flip with E11000 when the batch already has a running import;
        // the claim reports no winner and leaves the S3 job PENDING for a later tick.
        final MongoOperations mongo = mock(MongoOperations.class);
        when(mongo.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(BackgroundJob.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: philter-workspace.background_jobs "
                        + "index: uniq_running_data_import_per_batch_v2 dup key: { batchId: \"b1\" }"));

        assertNull(new MongoBackgroundJobStore(mongo).claimPending("s3-job"));
        verify(mongo).findAndModify(argThat((Query q) -> "s3-job".equals(q.getQueryObject().get("_id"))
                        && BackgroundJob.STATUS_PENDING.equals(q.getQueryObject().get("status"))),
                any(Update.class), any(FindAndModifyOptions.class), eq(BackgroundJob.class));
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
