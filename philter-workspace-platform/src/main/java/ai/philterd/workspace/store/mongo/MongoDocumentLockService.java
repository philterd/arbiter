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
import ai.philterd.workspace.service.DocumentLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Pessimistic review lock for documents. Acquisition uses MongoDB's atomic
 * {@code findAndModify} so two reviewers cannot acquire the same lock at the same
 * millisecond. The lock is sliding-expiry: a pulse from the open review page extends
 * it; when the user leaves the page (or the pulse stops) the existing expiry stands
 * and the lock eventually releases on its own.
 */
@Service
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
public class MongoDocumentLockService implements DocumentLockService {

    private final MongoOperations mongoOperations;

    public MongoDocumentLockService(final MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    @Override
    public Document acquire(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return null;
        final Instant now = Instant.now();
        final Instant newExpiry = now.plus(LOCK_TTL);

        final Criteria notHeldByOther = new Criteria().orOperator(
                Criteria.where("lockedBy").is(null),
                Criteria.where("lockExpiresAt").is(null),
                Criteria.where("lockExpiresAt").lte(now),
                Criteria.where("lockedBy").is(email));

        final Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(documentId),
                notHeldByOther));

        // We always rewrite lockedAt on acquire — every successful match means either a
        // fresh lock, a takeover after expiry, or a same-user re-open. None of those
        // benefit from preserving the older timestamp, so a single $set is correct and
        // avoids the ConflictingUpdateOperators error from mixing $set / $setOnInsert.
        final Update update = new Update()
                .set("lockedBy", email)
                .set("lockExpiresAt", newExpiry)
                .set("lockedAt", now);

        return mongoOperations.findAndModify(query, keepVersion(update),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    @Override
    public Document pulse(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return null;
        final Instant now = Instant.now();
        final Instant newExpiry = now.plus(LOCK_TTL);

        final Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(documentId),
                Criteria.where("lockedBy").is(email)));

        final Update update = new Update().set("lockExpiresAt", newExpiry);
        return mongoOperations.findAndModify(query, keepVersion(update),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    @Override
    public void release(final String documentId, final String email) {
        if (documentId == null || email == null || email.isBlank()) return;
        final Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(documentId),
                Criteria.where("lockedBy").is(email)));

        final Update update = new Update()
                .unset("lockedBy")
                .unset("lockedAt")
                .unset("lockExpiresAt");
        mongoOperations.findAndModify(query, keepVersion(update),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    @Override
    public Document breakLock(final String documentId) {
        if (documentId == null) return null;
        final Query query = Query.query(Criteria.where("_id").is(documentId));
        final Update update = new Update()
                .unset("lockedBy")
                .unset("lockedAt")
                .unset("lockExpiresAt");
        return mongoOperations.findAndModify(query, keepVersion(update),
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    /**
     * Spring Data adds {@code $inc} of the {@code @Version} field to any update that does not
     * already touch it. A lock change is not a change to the document, so touch it by zero.
     */
    static Update keepVersion(final Update update) {
        return update.inc("version", 0);
    }
}
