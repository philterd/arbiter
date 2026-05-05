/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists PII spans where a reviewer has requested a second opinion. The current user
 * sees only requests they did not initiate, scoped to documents in batches assigned
 * to their groups (admins see all). Also exposes a per-span audit history endpoint
 * that drives the History popup on the review page.
 */
@Controller
public class SecondOpinionsController {

    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogRepository auditLogRepository;

    public SecondOpinionsController(final SpanRepository spanRepository,
                                    final DocumentRepository documentRepository,
                                    final BatchRepository batchRepository,
                                    final UserGroupsService userGroupsService,
                                    final AuditLogRepository auditLogRepository) {
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/second-opinions")
    public String list(final Authentication authentication, final Model model) {
        final String email = authentication == null ? null : authentication.getName();
        final boolean admin = isAdmin(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(email);

        final List<Span> awaiting = spanRepository.findByStatus(Span.STATUS_NEEDS_SECOND_OPINION);

        final Map<String, Document> docCache = new HashMap<>();
        final Map<String, Batch> batchCache = new HashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Span span : awaiting) {
            if (span.getDocumentId() == null) continue;

            final Document document = docCache.computeIfAbsent(span.getDocumentId(),
                    id -> documentRepository.findById(id).orElse(null));
            if (document == null || document.getBatchId() == null) continue;

            final Batch batch = batchCache.computeIfAbsent(document.getBatchId(),
                    id -> batchRepository.findById(id).orElse(null));
            if (batch == null) continue;

            // Group-scope check (admins bypass).
            if (!admin) {
                if (batch.getGroupId() == null) continue;
                if (!myGroupIds.contains(batch.getGroupId())) continue;
            }

            // Show the current user's own requests for visibility, but mark them as
            // un-actionable — they need a different reviewer to resolve.
            final boolean ownRequest = email != null && span.getStatusChangedBy() != null
                    && email.equalsIgnoreCase(span.getStatusChangedBy());

            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("spanId", span.getId());
            row.put("documentId", document.getId());
            row.put("documentName", document.getFilename() == null ? document.getId() : document.getFilename());
            row.put("batchName", batch.getName() == null ? batch.getId() : batch.getName());
            row.put("type", span.getType() == null ? "" : span.getType());
            row.put("text", span.getText() == null ? "" : span.getText());
            row.put("requestedBy", span.getStatusChangedBy() == null ? "" : span.getStatusChangedBy());
            row.put("requestedAt", span.getStatusChangedAt());
            row.put("ownRequest", ownRequest);
            rows.add(row);
        }

        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> {
                    final Object t = r.get("requestedAt");
                    return t == null ? "" : t.toString();
                })
                .reversed());

        model.addAttribute("rows", rows);
        return "second-opinions";
    }

    /** Audit history for a single span — used by the History popup on the review page. */
    @GetMapping("/api/v1/spans/{id}/history")
    @ResponseBody
    public List<Map<String, Object>> history(@PathVariable final String id, final Authentication authentication) {
        final Span span = spanRepository.findById(id).orElse(null);
        if (span == null) return List.of();

        // Group-scope check via the document.
        final Document document = span.getDocumentId() == null ? null
                : documentRepository.findById(span.getDocumentId()).orElse(null);
        if (document == null) return List.of();
        if (!isAdmin(authentication)) {
            final Batch batch = document.getBatchId() == null ? null
                    : batchRepository.findById(document.getBatchId()).orElse(null);
            if (batch == null || batch.getGroupId() == null) return List.of();
            final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                    authentication == null ? null : authentication.getName());
            if (!myGroupIds.contains(batch.getGroupId())) return List.of();
        }

        final List<AuditLog> entries = auditLogRepository
                .findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", id);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog entry : entries) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", entry.getAction());
            row.put("timestamp", entry.getTimestamp());
            row.put("actor", entry.getUserEmail() == null ? "" : entry.getUserEmail());
            final Map<String, Object> details = entry.getDetails();
            if (details != null) {
                row.put("previousStatus", details.getOrDefault("previousStatus", ""));
                row.put("status", details.getOrDefault("status", ""));
                row.put("reason", details.getOrDefault("reason", ""));
                row.put("overturn", details.getOrDefault("overturn", false));
                row.put("previousType", details.getOrDefault("previousType", ""));
                row.put("type", details.getOrDefault("type", ""));
            }
            out.add(row);
        }
        return out;
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
