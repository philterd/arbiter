/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.repository;

import ai.philterd.workspace.model.Span;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpanRepository extends WorkspaceRepository<Span, String> {
    List<Span> findByDocumentId(String documentId);
    List<Span> findByStatusOrderByStatusChangedAtDesc(String status);
    long countByDocumentIdInAndStatus(java.util.Collection<String> documentIds, String status);
    long countByDocumentIdAndStatus(String documentId, String status);
    long countByDocumentIdInAndManuallyCreated(java.util.Collection<String> documentIds, boolean manuallyCreated);
    long deleteByDocumentId(String documentId);

    List<Span> findByDocumentIdInAndStatusIn(java.util.Collection<String> documentIds,
                                             java.util.Collection<String> statuses);
}
