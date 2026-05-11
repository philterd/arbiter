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

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.DataImportLogEntry;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.DataImportLogService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class BackgroundJobsController {

    /**
     * Cutoff for the Background Jobs page: rows older than this are hidden.
     * The page is meant for "what's happening right now" plus recent
     * troubleshooting; the long-tail history lives in the audit log
     * ({@code DATA_IMPORT_*} / {@code BATCH_EXPORT}) and survives indefinitely.
     */
    private static final int MAX_VISIBLE_AGE_DAYS = 7;

    private final BackgroundJobRepository repository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final BatchAccessService batchAccessService;
    private final DataImportLogService importLogService;

    public BackgroundJobsController(final BackgroundJobRepository repository,
                                    final BatchRepository batchRepository,
                                    final UserGroupsService userGroupsService,
                                    final BatchAccessService batchAccessService,
                                    final DataImportLogService importLogService) {
        this.repository = repository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.batchAccessService = batchAccessService;
        this.importLogService = importLogService;
    }

    /**
     * JSON endpoint that backs the "Log" button on each data-import row of the
     * Background Jobs page. Returns one page of per-file outcomes recorded by
     * {@link DataImportLogService} while the job ran. Access is scoped: callers
     * who can't see the job's batch get a 404 (no row leakage).
     *
     * <p>Response shape: {@code { items: [...], page, size, totalItems, totalPages }}.
     * {@code page} is zero-based; the service clamps {@code size} to a sane range.
     */
    @GetMapping("/api/v1/jobs/{jobId}/log")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> jobLog(
            @PathVariable final String jobId,
            @org.springframework.web.bind.annotation.RequestParam(value = "page", defaultValue = "0") final int page,
            @org.springframework.web.bind.annotation.RequestParam(value = "size", defaultValue = "10") final int size,
            final Authentication authentication) {
        final BackgroundJob job = repository.findById(jobId).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        final Batch batch = job.getBatchId() == null ? null
                : batchRepository.findById(job.getBatchId()).orElse(null);
        if (!batchAccessService.canAccessBatch(authentication, batch)) {
            return ResponseEntity.notFound().build();
        }
        final org.springframework.data.domain.Page<DataImportLogEntry> pageResult =
                importLogService.forJob(jobId, page, size);
        final java.util.List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (DataImportLogEntry e : pageResult.getContent()) {
            final java.util.Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", e.getTimestamp() == null ? "" : e.getTimestamp().toString());
            row.put("filename", e.getFilename() == null ? "" : e.getFilename());
            row.put("sourceDocId", e.getSourceDocId() == null ? "" : e.getSourceDocId());
            row.put("outcome", e.getOutcome() == null ? "" : e.getOutcome());
            row.put("message", e.getMessage() == null ? "" : e.getMessage());
            rows.add(row);
        }
        final java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("page", pageResult.getNumber());
        body.put("size", pageResult.getSize());
        body.put("totalItems", pageResult.getTotalElements());
        body.put("totalPages", pageResult.getTotalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jobs")
    public String list(final Authentication authentication, final Model model) {
        // Drop everything older than the cutoff up front. The repository sorts
        // by createdAt desc, so once we hit a row outside the window every row
        // after it is also outside — but the list is small enough in practice
        // that filter() reads more clearly than a takeWhile.
        final Instant cutoff = Instant.now().minus(MAX_VISIBLE_AGE_DAYS, ChronoUnit.DAYS);
        final List<BackgroundJob> all = repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(j -> j.getCreatedAt() == null || !j.getCreatedAt().isBefore(cutoff))
                .toList();
        final List<BackgroundJob> visible;
        if (AuthUtils.isAdminOrAuditor(authentication)) {
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
        // stable section order across them. Sections seeded here always render — even with
        // zero jobs — so each category gets its heading and an empty-state row. Categories
        // not seeded (e.g. CATEGORY_OTHER) only show up when at least one job exists.
        final Map<String, List<BackgroundJob>> grouped = new LinkedHashMap<>();
        grouped.put(BackgroundJob.CATEGORY_DATA_IMPORT, new ArrayList<>());
        grouped.put(BackgroundJob.CATEGORY_DATA_EXPORT, new ArrayList<>());
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
        model.addAttribute("maxVisibleAgeDays", MAX_VISIBLE_AGE_DAYS);
        return "jobs";
    }

}
