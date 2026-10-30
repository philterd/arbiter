/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store;

import ai.philterd.workspace.model.Document;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Atomic operations on the document ingest queue. They leave a document's {@code @Version}
 * unchanged; only saving the document changes it.
 */
public interface DocumentQueueStore {

    /**
     * Atomically flip a document from PENDING to PROCESSING and stamp {@code statusChangedAt}.
     * Returns the updated document, or {@code null} when another worker already claimed it.
     */
    Document claimPending(String documentId);

    /** Put PROCESSING documents whose {@code statusChangedAt} is before {@code cutoff} back to PENDING. */
    long requeueProcessingBefore(LocalDateTime cutoff);

    /** Documents with {@code start < createdAt <= end}. */
    long countCreatedBetween(LocalDateTime start, LocalDateTime end);

    /** Documents with {@code start < statusChangedAt <= end} whose status is not one of {@code excludedStatuses}. */
    long countStatusChangedBetween(LocalDateTime start, LocalDateTime end, Collection<String> excludedStatuses);

    /** Atomically delete a document only if it has {@code status}. Returns it, or {@code null}. */
    Document removeIfStatus(String documentId, String status);

    /** Delete every document with {@code status}. Returns the ids deleted. */
    List<String> removeAllWithStatus(String status);
}
