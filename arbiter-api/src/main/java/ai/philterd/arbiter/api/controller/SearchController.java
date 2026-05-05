/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.api.controller;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchHit;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchResults;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public SearchController(final OpenSearchIndexService openSearchIndexService,
                            final BatchRepository batchRepository,
                            final UserGroupsService userGroupsService) {
        this.openSearchIndexService = openSearchIndexService;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") final String query,
                                      @RequestParam(name = "offset", defaultValue = "0") final int offset,
                                      @RequestParam(name = "size", defaultValue = "10") final int size,
                                      final Authentication authentication) {
        final SearchResults results = openSearchIndexService.search(query, offset, size);

        // Filter to batches the caller can see. Admins see everything; others only batches in their groups.
        final boolean admin = isAdmin(authentication);
        final Set<String> allowedBatchIds = admin ? null : allowedBatchIds(authentication);
        final List<Map<String, Object>> hits = new ArrayList<>();
        for (SearchHit h : results.hits()) {
            final boolean restricted = !admin
                    && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()));
            final Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("restricted", restricted);
            if (restricted) {
                hit.put("id", null);
                hit.put("batchId", null);
                hit.put("filename", null);
                hit.put("status", null);
                hit.put("highlights", List.of());
            } else {
                hit.put("id", h.id());
                hit.put("batchId", h.batchId());
                hit.put("filename", h.filename());
                hit.put("status", h.status());
                hit.put("highlights", h.highlights());
            }
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

    private Set<String> allowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        final Set<String> ids = new java.util.HashSet<>();
        for (Batch b : batchRepository.findAll()) {
            if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
