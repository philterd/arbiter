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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.ApprovalRuleEvaluator;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class TriageController {

    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final SpanRepository spanRepository;
    private final UserGroupsService userGroupsService;
    private final ApprovalRuleEvaluator approvalRuleEvaluator;
    private final BatchAccessService batchAccessService;

    public TriageController(final DocumentRepository documentRepository,
                            final BatchRepository batchRepository,
                            final SpanRepository spanRepository,
                            final UserGroupsService userGroupsService,
                            final ApprovalRuleEvaluator approvalRuleEvaluator,
                            final BatchAccessService batchAccessService) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.spanRepository = spanRepository;
        this.userGroupsService = userGroupsService;
        this.approvalRuleEvaluator = approvalRuleEvaluator;
        this.batchAccessService = batchAccessService;
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("riskScore", "status", "batchId", "filename", "priority");

    @GetMapping("/queue")
    public Page<Map<String, Object>> getQueue(
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "10") final int size,
            @RequestParam(required = false) final String batchId,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String filename,
            @RequestParam(name = "myGroupsOnly", defaultValue = "false") final boolean myGroupsOnly,
            @RequestParam(name = "sort", defaultValue = "riskScore") final String sort,
            @RequestParam(name = "dir", defaultValue = "desc") final String dir,
            final Authentication authentication) {

        final String activeSort = SORTABLE_FIELDS.contains(sort) ? sort : "riskScore";
        final Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        final PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, activeSort));
        final boolean hasBatch = batchId != null && !batchId.isBlank();
        final boolean hasStatus = status != null && !status.isBlank();
        final String trimmedFilename = filename == null ? "" : filename.trim();
        final boolean hasFilename = !trimmedFilename.isEmpty();

        final boolean admin = isAdmin(authentication);
        // Non-admins are always restricted to their groups. Admins see everything by default,
        // but can opt in to the same scope via myGroupsOnly=true.
        final boolean restrict = !admin || myGroupsOnly;
        final Set<String> allowedBatchIds = restrict ? allowedBatchIds(authentication) : null;

        if (restrict && allowedBatchIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }
        if (hasBatch && restrict && !allowedBatchIds.contains(batchId)) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }

        Page<Document> documents;
        if (hasBatch && hasStatus && hasFilename) {
            documents = documentRepository.findByBatchIdAndStatusAndFilenameContainingIgnoreCase(
                    batchId, status, trimmedFilename, pageRequest);
        } else if (hasBatch && hasStatus) {
            documents = documentRepository.findByBatchIdAndStatus(batchId, status, pageRequest);
        } else if (hasBatch && hasFilename) {
            documents = documentRepository.findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase(
                    batchId, INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else if (hasBatch) {
            documents = documentRepository.findByBatchIdAndStatusNotIn(
                    batchId, INGEST_QUEUE_STATUSES, pageRequest);
        } else if (restrict && hasStatus && hasFilename) {
            documents = documentRepository.findByBatchIdInAndStatusAndFilenameContainingIgnoreCase(
                    allowedBatchIds, status, trimmedFilename, pageRequest);
        } else if (restrict && hasStatus) {
            documents = documentRepository.findByBatchIdInAndStatus(allowedBatchIds, status, pageRequest);
        } else if (restrict && hasFilename) {
            documents = documentRepository.findByBatchIdInAndStatusNotInAndFilenameContainingIgnoreCase(
                    allowedBatchIds, INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else if (restrict) {
            documents = documentRepository.findByBatchIdInAndStatusNotIn(
                    allowedBatchIds, INGEST_QUEUE_STATUSES, pageRequest);
        } else if (hasStatus && hasFilename) {
            documents = documentRepository.findByStatusAndFilenameContainingIgnoreCase(
                    status, trimmedFilename, pageRequest);
        } else if (hasStatus) {
            documents = documentRepository.findByStatus(status, pageRequest);
        } else if (hasFilename) {
            documents = documentRepository.findByStatusNotInAndFilenameContainingIgnoreCase(
                    INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else {
            documents = documentRepository.findByStatusNotIn(INGEST_QUEUE_STATUSES, pageRequest);
        }

        final Set<String> batchIds = documents.stream()
                .map(Document::getBatchId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        final Map<String, String> batchNames = new LinkedHashMap<>();
        final Map<String, Double> batchDocumentThresholds = new LinkedHashMap<>();
        final Map<String, Batch> batchesById = new LinkedHashMap<>();
        for (Batch b : batchRepository.findAllById(batchIds)) {
            batchNames.put(b.getId(), b.getName() == null ? b.getId() : b.getName());
            batchDocumentThresholds.put(b.getId(), b.getDocumentThreshold());
            batchesById.put(b.getId(), b);
        }

        return documents.map(toRow(batchNames, batchDocumentThresholds, batchesById));
    }

    @GetMapping("/batches")
    public List<Map<String, String>> getBatches(
            @RequestParam(name = "myGroupsOnly", defaultValue = "false") final boolean myGroupsOnly,
            final Authentication authentication) {
        final boolean admin = isAdmin(authentication);
        final boolean restrict = !admin || myGroupsOnly;
        final Set<String> myGroupIds = restrict
                ? userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName())
                : null;

        return batchRepository.findAll(
                        PageRequest.of(0, BatchAccessService.BATCH_SCAN_LIMIT, Sort.by("name")))
                .getContent().stream()
                .filter(b -> !restrict || (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())))
                .sorted(Comparator.comparing(
                        (Batch b) -> b.getName() == null ? "" : b.getName().toLowerCase()))
                .map(b -> {
                    final Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", b.getId());
                    entry.put("name", b.getName() == null ? b.getId() : b.getName());
                    return entry;
                })
                .toList();
    }

    private static final Set<String> USER_DECIDED_STATUSES = Set.of("APPROVED", "REJECTED", "FAILED", "FINALIZED");

    /**
     * Document statuses that mean "still in the ingest queue, not yet redacted." The Document
     * Queue page hides these whenever the user hasn't explicitly filtered by status — they
     * belong on the admin Ingest Queue page, not the reviewer-facing Documents view.
     */
    private static final Set<String> INGEST_QUEUE_STATUSES = Set.of("PENDING", "PROCESSING");

    private Function<Document, Map<String, Object>> toRow(final Map<String, String> batchNames,
                                                          final Map<String, Double> batchDocumentThresholds,
                                                          final Map<String, Batch> batchesById) {
        final LocalDateTime now = LocalDateTime.now();
        return doc -> {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", doc.getId());
            row.put("filename", doc.getFilename());
            row.put("status", doc.getStatus());
            row.put("riskScore", doc.getRiskScore());
            row.put("batchId", doc.getBatchId());
            row.put("batchName", batchNames.getOrDefault(doc.getBatchId(), doc.getBatchId()));
            final Double threshold = batchDocumentThresholds.get(doc.getBatchId());
            final boolean autoApproved = threshold != null
                    && !USER_DECIDED_STATUSES.contains(doc.getStatus())
                    && !"AUDIT_REQUIRED".equals(doc.getStatus())
                    && doc.getRiskScore() <= threshold;
            row.put("autoApproved", autoApproved);
            row.put("documentThreshold", threshold);
            row.put("sourceAvailable", doc.getOriginalText() != null && !doc.getOriginalText().isEmpty());
            row.put("daysInQueue", doc.getCreatedAt() == null
                    ? null
                    : Math.max(0, Duration.between(doc.getCreatedAt(), now).toDays()));

            // Approval-rule status: how many approvals does this document need vs already has?
            // Only meaningful while the document is reachable for review; once APPROVED the
            // count is whatever the final tally was.
            final Batch batch = batchesById.get(doc.getBatchId());
            final int acquired = doc.getApprovedBy() == null ? 0 : doc.getApprovedBy().size();
            final int required;
            if ("APPROVED".equals(doc.getStatus())) {
                required = Math.max(1, acquired);
            } else if (batch == null) {
                required = 1;
            } else {
                final List<Span> spans = spanRepository.findByDocumentId(doc.getId());
                required = approvalRuleEvaluator.approvalsRequired(batch, doc, spans);
            }
            row.put("approvalsRequired", required);
            row.put("approvalsAcquired", acquired);

            // Pessimistic review-lock state for the padlock indicator. lockedByOther = true
            // means another user currently holds the lock; the row also surfaces who holds
            // it (lockedBy) and when the current expiry runs out (lockExpiresAt) for the
            // tooltip and break-lock affordance.
            final java.time.Instant nowInstant = java.time.Instant.now();
            final boolean lockActive = doc.isLocked(nowInstant);
            row.put("lockActive", lockActive);
            row.put("lockedBy", lockActive ? doc.getLockedBy() : null);
            row.put("lockExpiresAt", lockActive ? doc.getLockExpiresAt() : null);
            row.put("priority", doc.getPriority());
            return row;
        };
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private Set<String> allowedBatchIds(final Authentication auth) {
        return batchAccessService.allowedBatchIds(auth);
    }
}
