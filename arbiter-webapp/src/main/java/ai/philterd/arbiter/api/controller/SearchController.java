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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchHit;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchResults;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final OpenSearchIndexService openSearchIndexService;
    private final DocumentRepository documentRepository;
    private final AuditLogService auditLogService;
    private final BatchAccessService batchAccessService;

    public SearchController(final OpenSearchIndexService openSearchIndexService,
                            final DocumentRepository documentRepository,
                            final AuditLogService auditLogService,
                            final BatchAccessService batchAccessService) {
        this.openSearchIndexService = openSearchIndexService;
        this.documentRepository = documentRepository;
        this.auditLogService = auditLogService;
        this.batchAccessService = batchAccessService;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") final String query,
                                      @RequestParam(name = "offset", defaultValue = "0") final int offset,
                                      @RequestParam(name = "size", defaultValue = "10") final int size,
                                      final Authentication authentication) {
        // Restrict at the OpenSearch query layer for non-admins so the reported `total` and
        // the hit list both exclude foreign documents — an attacker can no longer probe
        // queries to detect the existence of content in batches they can't see.
        // Auditors see cross-group results too — this controls the OpenSearch
        // batch-id filter, a read-only decision.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> allowedBatchIds = admin ? null : batchAccessService.allowedBatchIds(authentication);
        final SearchResults results = openSearchIndexService.search(query, offset, size, allowedBatchIds);
        if (offset == 0) {
            auditLogService.log("DOCUMENT_SEARCH", "Document", null,
                    java.util.Map.of("query", query == null ? "" : query, "total", results.total()));
        }

        // Live status from MongoDB — the indexed status can be stale if the document
        // was updated after it was last indexed.
        final java.util.Set<String> docIds = new java.util.HashSet<>();
        for (SearchHit h : results.hits()) {
            if (h.id() != null && !h.id().isBlank()) docIds.add(h.id());
        }
        final java.util.Map<String, String> liveStatuses = new java.util.HashMap<>();
        for (Document d : documentRepository.findAllById(docIds)) {
            liveStatuses.put(d.getId(), d.getStatus() == null ? "" : d.getStatus());
        }

        final List<Map<String, Object>> hits = new ArrayList<>();
        for (SearchHit h : results.hits()) {
            // Defense in depth: verify the hit's batchId is in the allowed set even though
            // the OpenSearch terms filter should already guarantee it. Skip silently on a
            // mismatch (could only happen via an indexing bug or schema drift).
            if (!admin && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()))) {
                continue;
            }
            final Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", h.id());
            hit.put("batchId", h.batchId());
            hit.put("filename", h.filename());
            hit.put("status", liveStatuses.getOrDefault(h.id(), h.status()));
            hit.put("highlights", h.highlights());
            hits.add(hit);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query == null ? "" : query);
        out.put("offset", results.from());
        out.put("size", results.size());
        out.put("total", results.total());
        out.put("hits", hits);
        return out;
    }

}
