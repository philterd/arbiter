/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.dto.IngestRequest;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.RedactionApiService;
import ai.philterd.arbiter.service.UserGroupsService;
import ai.philterd.arbiter.util.Hashing;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private final RedactionApiService redactionApiService;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final GeneralSettingsService generalSettingsService;

    public IngestionController(final RedactionApiService redactionApiService,
                               final DocumentRepository documentRepository,
                               final BatchRepository batchRepository,
                               final UserGroupsService userGroupsService,
                               final AuditLogService auditLogService,
                               final GeneralSettingsService generalSettingsService) {
        this.redactionApiService = redactionApiService;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.generalSettingsService = generalSettingsService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@Valid @RequestBody final IngestRequest request, final Authentication authentication) {

        final Batch batch = batchRepository.findById(request.batchId()).orElse(null);
        if (batch == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Batch not found: " + request.batchId()));
        }
        if (!canAccessBatch(authentication, batch)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to that batch."));
        }
        if (batch.isClosed()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Batch \"" + batch.getName() + "\" is closed and cannot accept new documents.",
                    "batchId", batch.getId(),
                    "closed", true));
        }

        final long maxBytes = generalSettingsService.load().getMaxUploadFileSizeBytes();
        final long size = request.text() == null ? 0L
                : request.text().getBytes(StandardCharsets.UTF_8).length;
        if (size > maxBytes) {
            return ResponseEntity.status(413).body(Map.of(
                    "error", "Document exceeds the configured max upload size.",
                    "maxBytes", maxBytes,
                    "sizeBytes", size));
        }

        // Persist a PENDING document; the background ingest-queue worker (in the webapp module)
        // will pick it up in arrival order, run Philter, and transition the document out of PENDING.
        final int priority = (request.priority() != null) ? request.priority() : 2;
        final String taskId = UUID.randomUUID().toString();
        final Document document = new Document();
        document.setId(taskId);
        document.setBatchId(request.batchId());
        document.setCreatedAt(LocalDateTime.now());
        document.setFilename(request.name());
        document.setOriginalText(request.text());
        document.setContentSha512(Hashing.sha512Hex(request.text() == null ? "" : request.text()));
        document.setPriority(priority);
        document.changeStatus("PENDING");
        documentRepository.save(document);

        auditLogService.log("DOCUMENT_INGEST", "Document", taskId,
                Map.of(
                        "batchId", request.batchId(),
                        "name", request.name() == null ? "" : request.name(),
                        "textLength", request.text() == null ? 0 : request.text().length()));

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    private boolean canAccessBatch(final Authentication auth, final Batch batch) {
        if (isAdmin(auth)) return true;
        if (batch == null || batch.getGroupId() == null) return false;
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
        return myGroupIds.contains(batch.getGroupId());
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
