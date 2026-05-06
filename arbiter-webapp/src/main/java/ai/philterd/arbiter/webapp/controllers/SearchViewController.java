/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.service.OpenSearchIndexService;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchHit;
import ai.philterd.arbiter.service.OpenSearchIndexService.SearchResults;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class SearchViewController {

    private static final int PAGE_SIZE = 10;

    private final OpenSearchIndexService openSearchIndexService;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public SearchViewController(final OpenSearchIndexService openSearchIndexService,
                                final BatchRepository batchRepository,
                                final UserGroupsService userGroupsService) {
        this.openSearchIndexService = openSearchIndexService;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    @GetMapping("/search")
    public String search(@RequestParam(name = "q", required = false) final String q,
                         @RequestParam(name = "offset", defaultValue = "0") final int offset,
                         final Authentication authentication,
                         final Model model) {
        final String query = q == null ? "" : q.trim();
        final int safeOffset = Math.max(0, offset);

        final List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        if (!query.isEmpty()) {
            final SearchResults results = openSearchIndexService.search(query, safeOffset, PAGE_SIZE);
            total = results.total();

            final boolean admin = isAdmin(authentication);
            final Set<String> allowedBatchIds = admin ? null : allowedBatchIds(authentication);

            // Build a name lookup for the batches we encounter so the result rows can show a
            // friendly batch name without an extra DB hit per result.
            final Set<String> batchIds = new HashSet<>();
            for (SearchHit h : results.hits()) {
                if (h.batchId() != null && !h.batchId().isBlank()) batchIds.add(h.batchId());
            }
            final Map<String, String> batchNames = new LinkedHashMap<>();
            for (Batch b : batchRepository.findAllById(batchIds)) {
                batchNames.put(b.getId(), b.getName() == null ? b.getId() : b.getName());
            }

            for (SearchHit h : results.hits()) {
                final boolean restricted = !admin
                        && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()));
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("restricted", restricted);
                if (restricted) {
                    // Acknowledge the hit exists but reveal nothing about the document itself.
                    row.put("id", null);
                    row.put("filename", null);
                    row.put("status", null);
                    row.put("batchId", null);
                    row.put("batchName", null);
                    row.put("highlights", List.of());
                } else {
                    row.put("id", h.id());
                    row.put("filename", h.filename());
                    row.put("status", h.status());
                    row.put("batchId", h.batchId());
                    row.put("batchName", batchNames.getOrDefault(h.batchId(), h.batchId()));
                    row.put("highlights", h.highlights());
                }
                rows.add(row);
            }
        }

        model.addAttribute("query", query);
        model.addAttribute("offset", safeOffset);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("total", total);
        model.addAttribute("results", rows);
        model.addAttribute("hasPrev", safeOffset > 0);
        model.addAttribute("hasNext", safeOffset + PAGE_SIZE < total);
        model.addAttribute("prevOffset", Math.max(0, safeOffset - PAGE_SIZE));
        model.addAttribute("nextOffset", safeOffset + PAGE_SIZE);
        model.addAttribute("displayStart", rows.isEmpty() ? 0 : safeOffset + 1);
        model.addAttribute("displayEnd", safeOffset + rows.size());
        return "search";
    }

    private Set<String> allowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        final Set<String> ids = new HashSet<>();
        for (Batch b : batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
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
