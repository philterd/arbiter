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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralises the "caller is admin or in this document's batch group" check that nearly
 * every read- or write-side endpoint performs.
 *
 * <p>All failure modes (missing batch, batch with no group, caller not in group) surface as
 * the same generic 404 — never a 403 — so an attacker probing ids cannot distinguish
 * "exists-but-no-access" from "doesn't exist."
 *
 * <p>Span-scoped endpoints use {@link #loadAccessibleParentForSpan(Span, Authentication)},
 * which shares the same admin/group logic but reports failures with {@code "Span not found."}
 * to keep the oracle closed at the span layer too.
 */
@Service
public class DocumentAccessService {

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final BatchAccessService batchAccessService;

    public DocumentAccessService(final BatchRepository batchRepository,
                                 final DocumentRepository documentRepository,
                                 final BatchAccessService batchAccessService) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.batchAccessService = batchAccessService;
    }

    /**
     * Throws 404 {@code "Document not found."} unless the caller is an admin or in the
     * group that owns the document's batch. Admin callers short-circuit before any
     * repository lookup.
     */
    public void requireDocumentAccess(final Authentication auth, final Document document) {
        if (AuthUtils.isAdmin(auth)) return;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (!batchAccessService.canAccessBatch(auth, batch)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }
    }

    /**
     * Resolves the document a span belongs to and verifies the caller can see it. All
     * failure modes — span's parent doc missing, batch missing, caller not in the batch's
     * group — surface as {@code "Span not found."} 404 so an attacker cannot tell a real
     * inaccessible span id from a never-existed id.
     */
    public Document loadAccessibleParentForSpan(final Span span, final Authentication auth) {
        final Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Span not found."));
        if (AuthUtils.isAdmin(auth)) return document;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (!batchAccessService.canAccessBatch(auth, batch)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Span not found.");
        }
        return document;
    }
}
