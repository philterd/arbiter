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
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PendingUploadRepository;
import ai.philterd.arbiter.service.AuditLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin/ingest-queue")
public class AdminIngestQueueController {

    private static final int PAGE_SIZE = 200;

    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final PendingUploadRepository pendingUploadRepository;
    private final MongoOperations mongoOperations;
    private final AuditLogService auditLogService;

    public AdminIngestQueueController(final DocumentRepository documentRepository,
                                      final BatchRepository batchRepository,
                                      final PendingUploadRepository pendingUploadRepository,
                                      final MongoOperations mongoOperations,
                                      final AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.pendingUploadRepository = pendingUploadRepository;
        this.mongoOperations = mongoOperations;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String view(final Model model) {
        final List<Document> pending = documentRepository.findByStatus("PENDING",
                PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
                .getContent();
        final List<Document> processing = documentRepository.findByStatus("PROCESSING",
                PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "statusChangedAt")))
                .getContent();
        final List<Document> failed = documentRepository.findByStatus("FAILED",
                PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "statusChangedAt")))
                .getContent();

        final Set<String> batchIds = new HashSet<>();
        for (Document d : pending) if (d.getBatchId() != null) batchIds.add(d.getBatchId());
        for (Document d : processing) if (d.getBatchId() != null) batchIds.add(d.getBatchId());
        for (Document d : failed) if (d.getBatchId() != null) batchIds.add(d.getBatchId());
        final Map<String, String> batchNames = new LinkedHashMap<>();
        for (Batch b : batchRepository.findAllById(batchIds)) {
            batchNames.put(b.getId(), b.getName() == null ? b.getId() : b.getName());
        }

        final LocalDateTime now = LocalDateTime.now();
        final List<Map<String, Object>> pendingRows = new ArrayList<>();
        for (Document d : pending) pendingRows.add(toRow(d, batchNames, now));
        final List<Map<String, Object>> processingRows = new ArrayList<>();
        for (Document d : processing) processingRows.add(toRow(d, batchNames, now));
        final List<Map<String, Object>> failedRows = new ArrayList<>();
        for (Document d : failed) failedRows.add(toRow(d, batchNames, now));

        model.addAttribute("pending", pendingRows);
        model.addAttribute("processing", processingRows);
        model.addAttribute("failed", failedRows);
        model.addAttribute("pendingTotal", pending.size());
        model.addAttribute("processingTotal", processing.size());
        model.addAttribute("failedTotal", failed.size());
        // Skipped is the cumulative count across all batches/time. SKIPPED rows are
        // placeholder records written when an OpenSearch / Elasticsearch import detects
        // a duplicate (same sourceIndex + sourceDocId).
        model.addAttribute("skippedTotal", documentRepository.countByStatus("SKIPPED"));
        // Re-read the clock for the 24-hour window so the upper bound is the count's
        // own "now" — not the timestamp captured at the top of view() before the row
        // queries ran. The window is [now-24h, now], inclusive on the upper end so a
        // document created at the request instant is counted.
        //
        // Using MongoOperations directly because Spring Data's method-name derivation
        // can't compose two predicates on the same property (Mongo's BSON Document
        // can hold only one key per field) — the Criteria builder, in contrast, lets
        // gt() and lte() coexist on a single property.
        final LocalDateTime windowEnd = LocalDateTime.now();
        final LocalDateTime windowStart = windowEnd.minusHours(24);
        final long last24hTotal = mongoOperations.count(
                Query.query(Criteria.where("createdAt").gt(windowStart).lte(windowEnd)),
                Document.class);
        model.addAttribute("last24hTotal", last24hTotal);

        // Throughput: documents that have moved out of the PENDING/PROCESSING queue with
        // a statusChangedAt inside the same 24-hour window, divided by 24 for an hourly
        // average. This uses statusChangedAt rather than createdAt so a backlog being
        // worked off lights up the meter even if intake has slowed.
        final long processedLast24h = mongoOperations.count(
                Query.query(Criteria.where("statusChangedAt").gt(windowStart).lte(windowEnd)
                        .and("status").nin("PENDING", "PROCESSING")),
                Document.class);
        model.addAttribute("throughputPerHour", processedLast24h / 24.0);
        return "admin-ingest-queue";
    }

    private static Map<String, Object> toRow(final Document d,
                                             final Map<String, String> batchNames,
                                             final LocalDateTime now) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        row.put("filename", d.getFilename());
        row.put("batchId", d.getBatchId());
        row.put("batchName", batchNames.getOrDefault(d.getBatchId(), d.getBatchId()));
        row.put("createdAt", d.getCreatedAt());
        row.put("statusChangedAt", d.getStatusChangedAt());
        row.put("age", d.getCreatedAt() == null ? null : formatDuration(Duration.between(d.getCreatedAt(), now)));
        row.put("failureSummary", failureSummary(d.getFailureMessage()));
        return row;
    }

    private static String failureSummary(final String message) {
        if (message == null || message.isBlank()) return null;
        final int newline = message.indexOf('\n');
        final String firstLine = newline < 0 ? message : message.substring(0, newline);
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120) + "…";
    }

    @GetMapping("/{id}/log")
    public String log(@PathVariable("id") final String id, final Model model,
                      final RedirectAttributes redirectAttributes) {
        final Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/admin/ingest-queue";
        }
        final String batchName = doc.getBatchId() == null ? null
                : batchRepository.findById(doc.getBatchId()).map(Batch::getName).orElse(doc.getBatchId());
        model.addAttribute("doc", doc);
        model.addAttribute("batchName", batchName);
        return "admin-ingest-queue-log";
    }

    /**
     * Admin-only: remove a queued document. Only documents still in {@code PENDING} are removable —
     * the atomic findAndModify guarantees we don't yank a document out from under a worker that
     * just claimed it. The sidecar bytes (if any) are deleted alongside the document.
     */
    @PostMapping("/{id}/delete")
    public String remove(@PathVariable("id") final String id,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final Query q = Query.query(Criteria.where("_id").is(id).and("status").is("PENDING"));
        final Document removed = mongoOperations.findAndRemove(q, Document.class);
        if (removed == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Document not found in the ingest queue, or it has already started processing.");
            return "redirect:/admin/ingest-queue";
        }
        // Best-effort sidecar cleanup — only some uploads have one (PDFs).
        try { pendingUploadRepository.deleteById(id); } catch (Exception ignored) {}

        auditLogService.log("INGEST_QUEUE_REMOVE", "Document", id,
                Map.of(
                        "batchId", removed.getBatchId() == null ? "" : removed.getBatchId(),
                        "filename", removed.getFilename() == null ? "" : removed.getFilename(),
                        "removedBy", authentication == null ? "unknown" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Removed \"" + (removed.getFilename() == null ? "document" : removed.getFilename())
                        + "\" from the ingest queue.");
        return "redirect:/admin/ingest-queue";
    }

    /**
     * Admin-only: remove a single failed document from the failures list. Hard-deletes the
     * Document (and any sidecar). The status filter prevents accidentally deleting a document
     * that has since been re-processed and is no longer FAILED.
     */
    @PostMapping("/{id}/clear-failure")
    public String clearFailure(@PathVariable("id") final String id,
                               final Authentication authentication,
                               final RedirectAttributes redirectAttributes) {
        final Query q = Query.query(Criteria.where("_id").is(id).and("status").is("FAILED"));
        final Document removed = mongoOperations.findAndRemove(q, Document.class);
        if (removed == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Document not found in the failures list (it may have been re-processed).");
            return "redirect:/admin/ingest-queue";
        }
        try { pendingUploadRepository.deleteById(id); } catch (Exception ignored) {}

        auditLogService.log("INGEST_QUEUE_FAILURE_CLEAR", "Document", id,
                Map.of(
                        "batchId", removed.getBatchId() == null ? "" : removed.getBatchId(),
                        "filename", removed.getFilename() == null ? "" : removed.getFilename(),
                        "clearedBy", authentication == null ? "unknown" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Removed \"" + (removed.getFilename() == null ? "document" : removed.getFilename())
                        + "\" from the failures list.");
        return "redirect:/admin/ingest-queue";
    }

    /**
     * Admin-only: hard-delete every document currently in {@code FAILED}. The UI prompts before
     * submitting this form. Sidecars for any of those documents are also dropped best-effort.
     */
    @PostMapping("/failures/clear")
    public String clearAllFailures(final Authentication authentication,
                                   final RedirectAttributes redirectAttributes) {
        final Query q = Query.query(Criteria.where("status").is("FAILED"));
        // Capture ids first so we can clean up sidecars and produce a useful audit-log entry.
        final List<Document> failed = mongoOperations.find(q, Document.class);
        final List<String> ids = new ArrayList<>();
        for (Document d : failed) ids.add(d.getId());

        final long removed = mongoOperations.remove(q, Document.class).getDeletedCount();
        for (String id : ids) {
            try { pendingUploadRepository.deleteById(id); } catch (Exception ignored) {}
        }

        auditLogService.log("INGEST_QUEUE_FAILURES_CLEAR_ALL", "Settings", "ingest-queue",
                Map.of(
                        "count", removed,
                        "clearedBy", authentication == null ? "unknown" : authentication.getName()));
        if (removed == 0) {
            redirectAttributes.addFlashAttribute("error", "No failed documents to clear.");
        } else {
            redirectAttributes.addFlashAttribute("success",
                    "Cleared " + removed + " failed document" + (removed == 1 ? "" : "s") + ".");
        }
        return "redirect:/admin/ingest-queue";
    }

    private static String formatDuration(final Duration d) {
        final long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86400) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
    }
}
