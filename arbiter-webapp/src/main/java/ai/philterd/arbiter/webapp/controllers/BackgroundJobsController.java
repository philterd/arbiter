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

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class BackgroundJobsController {

    private final BackgroundJobRepository repository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;

    public BackgroundJobsController(final BackgroundJobRepository repository,
                                    final BatchRepository batchRepository,
                                    final UserGroupsService userGroupsService) {
        this.repository = repository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
    }

    @GetMapping("/jobs")
    public String list(final Authentication authentication, final Model model) {
        final List<BackgroundJob> all = repository.findAllByOrderByCreatedAtDesc();
        final List<BackgroundJob> visible;
        if (isAdmin(authentication)) {
            visible = all;
        } else {
            // Resolve each job's batch's groupId in one round-trip and keep only the jobs
            // whose batch is in a group the current user belongs to.
            final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                    authentication == null ? null : authentication.getName());
            final Set<String> batchIds = all.stream()
                    .map(BackgroundJob::getBatchId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            final Map<String, String> groupIdByBatchId = new HashMap<>();
            for (Batch b : batchRepository.findAllById(batchIds)) {
                groupIdByBatchId.put(b.getId(), b.getGroupId());
            }
            visible = all.stream()
                    .filter(j -> {
                        final String groupId = groupIdByBatchId.get(j.getBatchId());
                        return groupId != null && myGroupIds.contains(groupId);
                    })
                    .toList();
        }

        // Group by category, preserving the createdAt-desc ordering inside each group and a
        // stable section order across them. Future categories will appear automatically.
        final Map<String, List<BackgroundJob>> grouped = new LinkedHashMap<>();
        grouped.put(BackgroundJob.CATEGORY_DATA_IMPORT, new ArrayList<>());
        for (BackgroundJob job : visible) {
            grouped.computeIfAbsent(job.getCategory(), k -> new ArrayList<>()).add(job);
        }
        // The fallback category goes last when present.
        if (grouped.containsKey(BackgroundJob.CATEGORY_OTHER)) {
            final List<BackgroundJob> other = grouped.remove(BackgroundJob.CATEGORY_OTHER);
            grouped.put(BackgroundJob.CATEGORY_OTHER, other);
        }

        // Mirror the grouping into a list of {category, label, jobs} rows that the template
        // can iterate. Categories with no jobs are still rendered so the page has a stable
        // section layout (e.g. "Data Import Jobs" with an empty-state message).
        final List<Map<String, Object>> sections = new ArrayList<>();
        for (Map.Entry<String, List<BackgroundJob>> e : grouped.entrySet()) {
            final Map<String, Object> section = new LinkedHashMap<>();
            section.put("category", e.getKey());
            section.put("label", BackgroundJob.categoryLabel(e.getKey()));
            section.put("jobs", e.getValue());
            sections.add(section);
        }
        model.addAttribute("sections", sections);
        return "jobs";
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
