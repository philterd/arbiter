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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.RedactionCertificate;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.RedactionCertificateRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Reads back a finalized document's redaction certificate. Group-scoped: non-admin
 * callers only see certificates for documents in their groups.
 */
@RestController
@RequestMapping("/api/v1")
public class RedactionCertificateController {

    private final RedactionCertificateRepository certificateRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public RedactionCertificateController(final RedactionCertificateRepository certificateRepository,
                                          final DocumentRepository documentRepository,
                                          final BatchRepository batchRepository,
                                          final UserGroupsService userGroupsService) {
        this.certificateRepository = certificateRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    @GetMapping("/documents/{id}/certificate")
    public ResponseEntity<RedactionCertificate> getCertificate(@PathVariable final String id,
                                                               final Authentication authentication) {
        final Document document = documentRepository.findById(id).orElse(null);
        if (document == null) return ResponseEntity.notFound().build();

        if (!isAdmin(authentication)) {
            final Batch batch = document.getBatchId() == null ? null
                    : batchRepository.findById(document.getBatchId()).orElse(null);
            if (batch == null || batch.getGroupId() == null) return ResponseEntity.notFound().build();
            final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                    authentication == null ? null : authentication.getName());
            if (!myGroupIds.contains(batch.getGroupId())) return ResponseEntity.notFound().build();
        }

        final List<RedactionCertificate> certs =
                certificateRepository.findByDocumentIdOrderByFinalizedAtDesc(id);
        if (certs.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(certs.get(0));
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
