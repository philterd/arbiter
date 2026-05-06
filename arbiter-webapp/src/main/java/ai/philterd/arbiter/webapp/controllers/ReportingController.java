/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Domains;
import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.repository.AuditLogRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class ReportingController {

    private static final List<String> KNOWN_STATUSES = List.of(
            "PENDING", "REVIEW_REQUIRED", "AUDIT_REQUIRED", "AUTO_APPROVED",
            "APPROVED", "REJECTED", "FAILED", "FINALIZED");
    private static final Set<String> USER_DECIDED = Set.of("APPROVED", "REJECTED", "FAILED", "FINALIZED");

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogRepository auditLogRepository;

    public ReportingController(final BatchRepository batchRepository,
                               final DocumentRepository documentRepository,
                               final SpanRepository spanRepository,
                               final PhilterInstanceRepository philterInstanceRepository,
                               final UserGroupsService userGroupsService,
                               final AuditLogRepository auditLogRepository) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.philterInstanceRepository = philterInstanceRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/reporting")
    public String view(@RequestParam(name = "start", required = false) final String start,
                       @RequestParam(name = "end", required = false) final String end,
                       @RequestParam(name = "domain", required = false) final List<String> domain,
                       final Authentication authentication,
                       final Model model) {
        final boolean admin = isAdmin(authentication);
        final boolean restrict = !admin;
        final Set<String> myGroupIds = restrict
                ? userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName())
                : Set.of();

        // Date range — default to the past 30 days (inclusive of today).
        final LocalDate today = LocalDate.now();
        LocalDate endDate = parseDateOr(end, today);
        LocalDate startDate = parseDateOr(start, today.minusDays(30));
        if (endDate.isBefore(startDate)) {
            // Swap silently rather than refuse — this is a forgiving filter.
            final LocalDate tmp = endDate; endDate = startDate; startDate = tmp;
        }
        final LocalDateTime rangeStart = startDate.atStartOfDay();
        final LocalDateTime rangeEndExclusive = endDate.plusDays(1).atStartOfDay();

        // Domain filter — accept any number of repeated `domain=Legal&domain=Healthcare` params,
        // ignoring values that aren't in the curated list.
        final Set<String> selectedDomains = new LinkedHashSet<>();
        if (domain != null) {
            for (String d : domain) {
                if (d != null && Domains.isValid(d.trim())) {
                    selectedDomains.add(d.trim());
                }
            }
        }

        final org.springframework.data.domain.Page<Batch> batchesPage =
                batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<Batch> batches = new ArrayList<>(batchesPage != null ? batchesPage.getContent() : List.of());
        if (restrict) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }
        if (!selectedDomains.isEmpty()) {
            batches.removeIf(b -> b.getDomain() == null || !selectedDomains.contains(b.getDomain()));
        }

        final Map<String, Long> globalStatusCounts = new LinkedHashMap<>();
        for (String s : KNOWN_STATUSES) globalStatusCounts.put(s, 0L);

        long totalDocuments = 0;
        long autoApprovedTotal = 0;
        long needsReviewTotal = 0;
        double riskScoreSum = 0.0;
        long openBatches = 0;
        long closedBatches = 0;

        final Map<String, String> philterNames = new LinkedHashMap<>();
        final org.springframework.data.domain.Page<PhilterInstance> philterPage =
                philterInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<PhilterInstance> philterInstances = philterPage != null ? philterPage.getContent() : List.of();
        for (PhilterInstance i : philterInstances) {
            philterNames.put(i.getId(), i.getName() == null ? i.getId() : i.getName());
        }

        long spansAcceptedTotal = 0;
        long spansRejectedTotal = 0;
        long spansManualTotal = 0;
        long spansTotal = 0;

        // Aggregations keyed by "philterDisplayName::policyOrNone"
        final Map<String, Map<String, Object>> policyAggregates = new LinkedHashMap<>();

        // Aggregations keyed by domain name (or "(none)" for batches without a domain).
        final Map<String, Map<String, Object>> domainAggregates = new LinkedHashMap<>();

        final List<Map<String, Object>> batchRows = new ArrayList<>();
        for (Batch batch : batches) {
            if (batch.isClosed()) closedBatches++;
            else openBatches++;

            final List<Document> allDocs = documentRepository.findByBatchId(batch.getId());
            // Restrict to documents whose createdAt falls within the selected range. Documents
            // with null createdAt (legacy data created before the field existed) are kept so they
            // aren't silently dropped from totals.
            final LocalDateTime rs = rangeStart;
            final LocalDateTime re = rangeEndExclusive;
            final List<Document> docs = allDocs.stream()
                    .filter(d -> d.getCreatedAt() == null
                            || (!d.getCreatedAt().isBefore(rs) && d.getCreatedAt().isBefore(re)))
                    .toList();
            final Map<String, Long> statusCounts = new LinkedHashMap<>();
            for (String s : KNOWN_STATUSES) statusCounts.put(s, 0L);

            long batchAutoApproved = 0;
            double batchRiskSum = 0.0;
            for (Document d : docs) {
                final String status = d.getStatus() == null ? "" : d.getStatus();
                statusCounts.merge(status, 1L, Long::sum);
                globalStatusCounts.merge(status, 1L, Long::sum);
                riskScoreSum += d.getRiskScore();
                batchRiskSum += d.getRiskScore();
                if ("REVIEW_REQUIRED".equals(status) || "AUDIT_REQUIRED".equals(status)) needsReviewTotal++;
                if (!USER_DECIDED.contains(status) && !"AUDIT_REQUIRED".equals(status)
                        && d.getRiskScore() <= batch.getDocumentThreshold()) {
                    batchAutoApproved++;
                    autoApprovedTotal++;
                }
            }
            totalDocuments += docs.size();

            // Span counts for this batch
            final List<String> docIds = docs.stream().map(Document::getId).filter(java.util.Objects::nonNull).toList();
            final long spansAccepted = docIds.isEmpty() ? 0L
                    : spanRepository.countByDocumentIdInAndStatus(docIds, "APPROVED");
            final long spansRejected = docIds.isEmpty() ? 0L
                    : spanRepository.countByDocumentIdInAndStatus(docIds, "REJECTED");
            final long spansManual = docIds.isEmpty() ? 0L
                    : spanRepository.countByDocumentIdInAndManuallyCreated(docIds, true);
            final long spansBatchTotal = spansAccepted + spansRejected;
            final long editRateBaseline = Math.max(1L, spansBatchTotal + spansManual);
            final double editRate = (double) spansManual / (double) editRateBaseline;

            spansAcceptedTotal += spansAccepted;
            spansRejectedTotal += spansRejected;
            spansManualTotal += spansManual;
            spansTotal += spansBatchTotal;

            final String philterName = batch.getPhilterInstanceId() == null
                    ? "Embedded Philter"
                    : philterNames.getOrDefault(batch.getPhilterInstanceId(), "(missing)");
            final String policyName = batch.getPolicyName() == null ? "(no policy)" : batch.getPolicyName();
            final String aggKey = philterName + "::" + policyName;
            final Map<String, Object> agg = policyAggregates.computeIfAbsent(aggKey, k -> {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("philterName", philterName);
                m.put("policyName", policyName);
                m.put("batches", 0L);
                m.put("documents", 0L);
                m.put("spansAccepted", 0L);
                m.put("spansRejected", 0L);
                m.put("spansManual", 0L);
                return m;
            });
            agg.put("batches", (Long) agg.get("batches") + 1);
            agg.put("documents", (Long) agg.get("documents") + (long) docs.size());
            agg.put("spansAccepted", (Long) agg.get("spansAccepted") + spansAccepted);
            agg.put("spansRejected", (Long) agg.get("spansRejected") + spansRejected);
            agg.put("spansManual", (Long) agg.get("spansManual") + spansManual);

            final String domainKey = batch.getDomain() == null ? "(none)" : batch.getDomain();
            final Map<String, Object> dAgg = domainAggregates.computeIfAbsent(domainKey, k -> {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", k);
                m.put("batches", 0L);
                m.put("documents", 0L);
                m.put("spansAccepted", 0L);
                m.put("spansRejected", 0L);
                m.put("spansManual", 0L);
                return m;
            });
            dAgg.put("batches", (Long) dAgg.get("batches") + 1);
            dAgg.put("documents", (Long) dAgg.get("documents") + (long) docs.size());
            dAgg.put("spansAccepted", (Long) dAgg.get("spansAccepted") + spansAccepted);
            dAgg.put("spansRejected", (Long) dAgg.get("spansRejected") + spansRejected);
            dAgg.put("spansManual", (Long) dAgg.get("spansManual") + spansManual);

            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("name", batch.getName() == null ? "" : batch.getName());
            row.put("closed", batch.isClosed());
            row.put("documentCount", (long) docs.size());
            row.put("statusCounts", statusCounts);
            row.put("autoApproved", batchAutoApproved);
            row.put("avgRiskScore", docs.isEmpty() ? 0.0 : batchRiskSum / docs.size());
            row.put("philterName", philterName);
            row.put("policyName", policyName);
            row.put("spansAccepted", spansAccepted);
            row.put("spansRejected", spansRejected);
            row.put("spansManual", spansManual);
            row.put("editRate", editRate);
            batchRows.add(row);
        }

        // Compute editRate for each policy aggregate.
        for (Map<String, Object> agg : policyAggregates.values()) {
            final long acc = (Long) agg.get("spansAccepted");
            final long rej = (Long) agg.get("spansRejected");
            final long man = (Long) agg.get("spansManual");
            final long denom = Math.max(1L, acc + rej + man);
            agg.put("editRate", (double) man / (double) denom);
        }
        final List<Map<String, Object>> policyRows = new ArrayList<>(policyAggregates.values());
        policyRows.sort(Comparator
                .comparingDouble((Map<String, Object> r) -> ((Number) r.get("editRate")).doubleValue())
                .reversed()
                .thenComparing(r -> ((String) r.get("philterName")).toLowerCase())
                .thenComparing(r -> ((String) r.get("policyName")).toLowerCase()));

        // Compute editRate for each domain aggregate.
        for (Map<String, Object> agg : domainAggregates.values()) {
            final long acc = (Long) agg.get("spansAccepted");
            final long rej = (Long) agg.get("spansRejected");
            final long man = (Long) agg.get("spansManual");
            final long denom = Math.max(1L, acc + rej + man);
            agg.put("editRate", (double) man / (double) denom);
        }
        final List<Map<String, Object>> domainRows = new ArrayList<>(domainAggregates.values());
        domainRows.sort(Comparator
                .comparingDouble((Map<String, Object> r) -> ((Number) r.get("editRate")).doubleValue())
                .reversed()
                .thenComparing(r -> ((String) r.get("domain")).toLowerCase()));

        batchRows.sort(Comparator
                .comparingLong((Map<String, Object> r) -> ((Number) r.get("documentCount")).longValue())
                .reversed()
                .thenComparing(r -> ((String) r.get("name")).toLowerCase()));

        final long globalEditDenom = Math.max(1L, spansTotal + spansManualTotal);
        final double globalEditRate = (double) spansManualTotal / (double) globalEditDenom;

        model.addAttribute("totalBatches", (long) batches.size());
        model.addAttribute("openBatches", openBatches);
        model.addAttribute("closedBatches", closedBatches);
        model.addAttribute("totalDocuments", totalDocuments);
        model.addAttribute("statusCounts", globalStatusCounts);
        model.addAttribute("autoApprovedTotal", autoApprovedTotal);
        model.addAttribute("needsReviewTotal", needsReviewTotal);
        model.addAttribute("avgRiskScore", totalDocuments == 0 ? 0.0 : riskScoreSum / totalDocuments);
        model.addAttribute("spansAcceptedTotal", spansAcceptedTotal);
        model.addAttribute("spansRejectedTotal", spansRejectedTotal);
        model.addAttribute("spansManualTotal", spansManualTotal);
        model.addAttribute("globalEditRate", globalEditRate);
        model.addAttribute("policyRows", policyRows);
        model.addAttribute("domainRows", domainRows);
        model.addAttribute("batchRows", batchRows);
        model.addAttribute("knownStatuses", KNOWN_STATUSES);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("startDate", startDate.toString());
        model.addAttribute("endDate", endDate.toString());
        model.addAttribute("availableDomains", Domains.VALUES);

        // Per-reviewer counts over the same date range. Each entry tallies decisions made by
        // a single user — DOCUMENT_APPROVAL (any approval click) and DOCUMENT_STATUS_CHANGE
        // entries that landed at REJECTED.
        final java.time.Instant rangeStartInstant = rangeStart.atZone(java.time.ZoneOffset.UTC).toInstant();
        final java.time.Instant rangeEndInstant = rangeEndExclusive.atZone(java.time.ZoneOffset.UTC).toInstant();
        final List<AuditLog> entries = auditLogRepository
                .findByTimestampBetweenAndActionIn(
                        rangeStartInstant, rangeEndInstant,
                        List.of("DOCUMENT_APPROVAL", "DOCUMENT_STATUS_CHANGE"));
        final Map<String, long[]> reviewerTotals = new LinkedHashMap<>(); // [approvals, rejections]
        for (AuditLog e : entries) {
            final String email = e.getUserEmail() == null ? "(unknown)" : e.getUserEmail();
            if ("DOCUMENT_APPROVAL".equals(e.getAction())) {
                reviewerTotals.computeIfAbsent(email, k -> new long[]{0, 0})[0]++;
            } else if ("DOCUMENT_STATUS_CHANGE".equals(e.getAction())
                    && e.getDetails() != null
                    && "REJECTED".equals(String.valueOf(e.getDetails().get("current")))) {
                reviewerTotals.computeIfAbsent(email, k -> new long[]{0, 0})[1]++;
            }
        }
        final List<Map<String, Object>> reviewerRows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : reviewerTotals.entrySet()) {
            final long approvals = en.getValue()[0];
            final long rejections = en.getValue()[1];
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("email", en.getKey());
            row.put("approvals", approvals);
            row.put("rejections", rejections);
            row.put("total", approvals + rejections);
            reviewerRows.add(row);
        }
        reviewerRows.sort(Comparator
                .comparingLong((Map<String, Object> r) -> ((Number) r.get("total")).longValue())
                .reversed()
                .thenComparing(r -> ((String) r.get("email")).toLowerCase()));
        model.addAttribute("reviewerRows", reviewerRows);
        model.addAttribute("selectedDomains", Collections.unmodifiableSet(selectedDomains));
        return "reporting";
    }

    private static LocalDate parseDateOr(final String value, final LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
