/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Document;

import java.time.Duration;

/**
 * Exclusive review locks on documents, acquired and released atomically in the store.
 */
public interface DocumentLockService {

    /** Sliding expiry pushed forward on every acquire/pulse. */
    Duration LOCK_TTL = Duration.ofMinutes(15);

    /**
     * Attempt to acquire (or refresh) the lock for {@code email}. Succeeds when the
     * document is not currently locked, the lock has expired, or the caller already
     * holds it. Returns the resulting document on success and {@code null} when the
     * lock is held by someone else and still valid.
     */
    Document acquire(String documentId, String email);

    /**
     * Push the existing lock's expiry forward, only if it's still held by {@code email}.
     * Used by the periodic pulse from the open review page. Returns the updated document
     * on success or {@code null} if the lock is no longer held by this user.
     */
    Document pulse(String documentId, String email);

    /**
     * Release a lock that the caller currently holds. No-op if the document isn't
     * locked or is locked by someone else.
     */
    void release(String documentId, String email);

    /** Force-clear the lock regardless of holder. Reserved for admin/supervisor break-lock. */
    Document breakLock(String documentId);
}
