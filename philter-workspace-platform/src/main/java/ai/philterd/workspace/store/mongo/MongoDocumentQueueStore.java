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

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.store.DocumentQueueStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
public class MongoDocumentQueueStore implements DocumentQueueStore {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_FAILED = "FAILED";

    private final MongoOperations mongoOperations;

    public MongoDocumentQueueStore(final MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    @Override
    public Document claimPending(final String documentId) {
        final Query q = Query.query(Criteria.where("_id").is(documentId)
                .and("status").is(STATUS_PENDING));
        final Update u = new Update()
                .set("status", STATUS_PROCESSING)
                .set("statusChangedAt", LocalDateTime.now());
        return mongoOperations.findAndModify(q, MongoDocumentLockService.keepVersion(u),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    @Override
    public Document requeueFailed(final String documentId) {
        final Query q = Query.query(Criteria.where("_id").is(documentId)
                .and("status").is(STATUS_FAILED));
        final Update u = new Update()
                .set("status", STATUS_PENDING)
                .set("statusChangedAt", LocalDateTime.now());
        return mongoOperations.findAndModify(q, MongoDocumentLockService.keepVersion(u),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    @Override
    public long requeueProcessingBefore(final LocalDateTime cutoff) {
        final Query q = Query.query(Criteria.where("status").is(STATUS_PROCESSING)
                .and("statusChangedAt").lt(cutoff));
        final Update u = new Update()
                .set("status", STATUS_PENDING)
                .set("statusChangedAt", LocalDateTime.now());
        return mongoOperations.updateMulti(q, MongoDocumentLockService.keepVersion(u), Document.class).getModifiedCount();
    }

    @Override
    public long countCreatedBetween(final LocalDateTime start, final LocalDateTime end) {
        return mongoOperations.count(
                Query.query(Criteria.where("createdAt").gt(start).lte(end)), Document.class);
    }

    @Override
    public long countStatusChangedBetween(final LocalDateTime start, final LocalDateTime end,
                                          final Collection<String> excludedStatuses) {
        return mongoOperations.count(
                Query.query(Criteria.where("statusChangedAt").gt(start).lte(end)
                        .and("status").nin(excludedStatuses)),
                Document.class);
    }

    @Override
    public Document removeIfStatus(final String documentId, final String status) {
        return mongoOperations.findAndRemove(
                Query.query(Criteria.where("_id").is(documentId).and("status").is(status)), Document.class);
    }

    @Override
    public List<String> removeAllWithStatus(final String status) {
        final List<String> removed = new ArrayList<>();
        for (Document d : mongoOperations.find(Query.query(Criteria.where("status").is(status)), Document.class)) {
            // One at a time, so a document whose status changed since the read is kept.
            if (removeIfStatus(d.getId(), status) != null) {
                removed.add(d.getId());
            }
        }
        return removed;
    }
}
