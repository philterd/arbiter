/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.service.DocumentAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Reads back a finalized document's redaction certificate. Group-scoped: non-admin
 * callers only see certificates for documents in their groups.
 */
@RestController
@RequestMapping("/api/v1")
public class RedactionCertificateController {

    private final RedactionCertificateRepository certificateRepository;
    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;

    public RedactionCertificateController(final RedactionCertificateRepository certificateRepository,
                                          final DocumentRepository documentRepository,
                                          final DocumentAccessService documentAccessService) {
        this.certificateRepository = certificateRepository;
        this.documentRepository = documentRepository;
        this.documentAccessService = documentAccessService;
    }

    @GetMapping("/documents/{id}/certificate")
    public ResponseEntity<RedactionCertificate> getCertificate(@PathVariable final String id,
                                                               final Authentication authentication) {
        final Document document = documentRepository.findById(id).orElse(null);
        // Uniform 404: missing-document and no-access both return notFound().build() so an
        // attacker probing ids can't tell them apart. requireDocumentAccess throws 404 in
        // the no-access case; we translate just that status to keep the response shape
        // consistent with the missing-document branch above. Any other status (a future
        // 409 for soft-deleted documents, etc.) is rethrown so it isn't silently masked.
        if (document == null) return ResponseEntity.notFound().build();
        try {
            documentAccessService.requireDocumentAccess(authentication, document);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }

        final List<RedactionCertificate> certs =
                certificateRepository.findByDocumentIdOrderByFinalizedAtDesc(id);
        if (certs.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(certs.get(0));
    }
}
