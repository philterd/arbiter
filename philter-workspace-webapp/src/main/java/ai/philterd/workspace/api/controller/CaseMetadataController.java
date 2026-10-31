/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.model.CaseMetadata;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.CaseMetadataService;
import ai.philterd.workspace.service.DocumentAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/** A document's case metadata (#16), read and replaced through the API. */
@RestController
@RequestMapping("/api/v1/documents/{id}/case-metadata")
public class CaseMetadataController {

    private final DocumentRepository documents;
    private final DocumentAccessService documentAccess;
    private final CaseMetadataService caseMetadata;

    public CaseMetadataController(final DocumentRepository documents, final DocumentAccessService documentAccess,
                                  final CaseMetadataService caseMetadata) {
        this.documents = documents;
        this.documentAccess = documentAccess;
        this.caseMetadata = caseMetadata;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable final String id, final Authentication authentication) {
        final Document document = load(id, authentication);
        final Map<String, Object> body = new HashMap<>();
        body.put("caseMetadata", document.getCaseMetadata());
        body.put("appliedPolicyName", document.getAppliedPolicyName());
        body.put("appliedPolicyVersion", document.getAppliedPolicyVersion());
        return body;
    }

    /** Replaces the case metadata; an empty object clears it. JSON only, as a CSRF defence. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> put(@PathVariable final String id,
                                                   @RequestBody final CaseMetadata given,
                                                   final Authentication authentication) {
        final Document document = load(id, authentication);
        final CaseMetadata updated;
        try {
            updated = given == null ? null : CaseMetadata.of(given.getCaseNumber(), given.getCaseType(),
                    given.getFiler(), given.getFeeStatus(), given.getIfp());
        } catch (CaseMetadata.InvalidException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "problems", e.problems()));
        }
        try {
            final boolean changed = caseMetadata.update(document, updated);
            final Map<String, Object> body = new HashMap<>();
            body.put("changed", changed);
            body.put("caseMetadata", document.getCaseMetadata());
            return ResponseEntity.ok(body);
        } catch (CaseMetadataService.NotChangeableException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "The document changed while its case metadata was being saved. Reload it and try again."));
        }
    }

    private Document load(final String id, final Authentication authentication) {
        final Document document = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));
        documentAccess.requireDocumentAccess(authentication, document);
        return document;
    }
}
