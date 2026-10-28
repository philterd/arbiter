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
import ai.philterd.workspace.store.BackgroundJobStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;

@Component
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
public class MongoBackgroundJobStore implements BackgroundJobStore {

    private final MongoOperations mongoOperations;

    public MongoBackgroundJobStore(final MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    @Override
    public long countRunning(final Collection<String> types) {
        return mongoOperations.count(
                Query.query(Criteria.where("status").is(BackgroundJob.STATUS_RUNNING)
                        .and("type").in(types)),
                BackgroundJob.class);
    }

    @Override
    public BackgroundJob claimPending(final String jobId) {
        try {
            final Query q = Query.query(Criteria.where("_id").is(jobId)
                    .and("status").is(BackgroundJob.STATUS_PENDING));
            final Update u = new Update()
                    .set("status", BackgroundJob.STATUS_RUNNING)
                    .set("startedAt", Instant.now());
            return mongoOperations.findAndModify(q, u,
                    FindAndModifyOptions.options().returnNew(true), BackgroundJob.class);
        } catch (DuplicateKeyException e) {
            // The partial unique index allows one RUNNING data import per batch.
            return null;
        }
    }
}
