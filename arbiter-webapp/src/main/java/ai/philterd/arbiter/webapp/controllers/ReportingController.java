/*
 * Copyright 2026 Philterd, LLC.
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
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
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
    // Statuses that mean the document still needs reviewer attention. Used to compute
    // the "not yet approved" total in the per-batch / per-priority breakdown.
    private static final Set<String> NOT_YET_APPROVED = Set.of(
            "PENDING", "REVIEW_REQUIRED", "AUDIT_REQUIRED");

    private static String priorityLabel(final int priority) {
        if (priority == 3) return "High";
        if (priority == 1) return "Low";
        return "Normal";
    }

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
        // Reports show cross-group data to admins and auditors alike. Auditors land
        // here too — the GET on /reporting is admitted by SecurityConfig.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
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

        // One row per (batch, priority) combination that has at least one document
        // in the current scope. Built incrementally as we walk each batch's documents.
        final List<Map<String, Object>> priorityBatchRows = new ArrayList<>();

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
            // Per-priority status buckets for this batch. Key is the priority int (1/2/3);
            // value is a status -> count map seeded with every KNOWN_STATUS so the template
            // can render a stable column order.
            final Map<Integer, Map<String, Long>> priorityStatusCounts = new LinkedHashMap<>();
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
                priorityStatusCounts
                        .computeIfAbsent(d.getPriority(), k -> {
                            final Map<String, Long> m = new LinkedHashMap<>();
                            for (String s : KNOWN_STATUSES) m.put(s, 0L);
                            return m;
                        })
                        .merge(status, 1L, Long::sum);
            }
            totalDocuments += docs.size();

            // Emit one (batch, priority) row per priority level that actually had a document.
            // Highest priority first so reviewers eyeballing the table see High urgency at the top.
            final List<Integer> orderedPriorities = new ArrayList<>(priorityStatusCounts.keySet());
            orderedPriorities.sort(Comparator.<Integer>naturalOrder().reversed());
            for (Integer p : orderedPriorities) {
                final Map<String, Long> sc = priorityStatusCounts.get(p);
                long total = 0;
                long notApproved = 0;
                for (Map.Entry<String, Long> en : sc.entrySet()) {
                    total += en.getValue();
                    if (NOT_YET_APPROVED.contains(en.getKey())) notApproved += en.getValue();
                }
                final Map<String, Object> r = new LinkedHashMap<>();
                r.put("batchId", batch.getId());
                r.put("batchName", batch.getName() == null ? "" : batch.getName());
                r.put("priority", p);
                r.put("priorityLabel", priorityLabel(p));
                r.put("statusCounts", sc);
                r.put("total", total);
                r.put("notApproved", notApproved);
                priorityBatchRows.add(r);
            }

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
        model.addAttribute("priorityBatchRows", priorityBatchRows);
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

        // Inter-Annotator Agreement (Cohen's Kappa) — only for batches with Blind Double
        // Review enabled. Score is computed at the token level: each whitespace-delimited
        // word is labeled "PII" if it sits inside any approved span the reviewer left,
        // else "O". Per-batch counts are pooled across every double-reviewed document
        // that has snapshots for both reviewers, then a single kappa is computed per batch.
        final List<Map<String, Object>> iaaRows = new ArrayList<>();
        for (Batch batch : batches) {
            if (!batch.isBlindDoubleReviewEnabled()) continue;
            final List<Document> batchDocs = documentRepository.findByBatchId(batch.getId());
            long bothPii = 0, firstPiiOnly = 0, secondPiiOnly = 0, bothO = 0;
            long doubleReviewedDocs = 0;
            for (Document d : batchDocs) {
                if (!d.isDoubleReview()) continue;
                if (d.getFirstReviewSpans() == null || d.getSecondReviewSpans() == null) continue;
                final String text = d.getOriginalText();
                if (text == null || text.isEmpty()) continue;
                doubleReviewedDocs++;
                final long[] counts = tokenLabelCounts(text, d.getFirstReviewSpans(), d.getSecondReviewSpans());
                bothPii += counts[0];
                firstPiiOnly += counts[1];
                secondPiiOnly += counts[2];
                bothO += counts[3];
            }
            final long totalTokens = bothPii + firstPiiOnly + secondPiiOnly + bothO;
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", batch.getId());
            row.put("batchName", batch.getName() == null ? "" : batch.getName());
            row.put("doubleReviewedDocs", doubleReviewedDocs);
            row.put("totalTokens", totalTokens);
            if (totalTokens == 0) {
                row.put("kappa", null);
            } else {
                row.put("kappa", cohenKappa(bothPii, firstPiiOnly, secondPiiOnly, bothO));
            }
            iaaRows.add(row);
        }
        iaaRows.sort(Comparator.comparing(r -> ((String) r.get("batchName")).toLowerCase()));
        model.addAttribute("iaaRows", iaaRows);
        return "reporting";
    }

    /**
     * Tokenize the document text on whitespace and tally token-level PII/O agreement counts
     * between the first reviewer's span set and the second reviewer's span set. A token is
     * "PII" if any character of it lies inside an approved span — partial overlap counts as
     * PII to be conservative for compliance use.
     *
     * @return a 4-element array {@code [bothPii, firstPiiOnly, secondPiiOnly, bothO]}.
     */
    private static long[] tokenLabelCounts(final String text,
                                           final List<int[]> firstSpans,
                                           final List<int[]> secondSpans) {
        long bothPii = 0, firstOnly = 0, secondOnly = 0, bothO = 0;
        int i = 0;
        final int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            int j = i;
            while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
            final boolean firstPii = anyOverlap(firstSpans, i, j);
            final boolean secondPii = anyOverlap(secondSpans, i, j);
            if (firstPii && secondPii) bothPii++;
            else if (firstPii) firstOnly++;
            else if (secondPii) secondOnly++;
            else bothO++;
            i = j;
        }
        return new long[]{bothPii, firstOnly, secondOnly, bothO};
    }

    private static boolean anyOverlap(final List<int[]> spans, final int start, final int end) {
        if (spans == null) return false;
        for (int[] s : spans) {
            if (s == null || s.length < 2) continue;
            if (s[0] < end && s[1] > start) return true;
        }
        return false;
    }

    /**
     * Cohen's Kappa for a 2x2 confusion matrix between two annotators.
     * Returns 1.0 when both annotators perfectly agree on a single class (a degenerate
     * case where the standard formula evaluates to 0/0); the convention reflects that
     * unanimous agreement is maximal agreement.
     */
    private static double cohenKappa(final long a, final long b, final long c, final long d) {
        final long n = a + b + c + d;
        if (n == 0) return 0.0;
        final double po = (double) (a + d) / (double) n;
        final double pPiiAnnotator1 = (double) (a + b) / (double) n;
        final double pPiiAnnotator2 = (double) (a + c) / (double) n;
        final double pOAnnotator1 = (double) (c + d) / (double) n;
        final double pOAnnotator2 = (double) (b + d) / (double) n;
        final double pe = pPiiAnnotator1 * pPiiAnnotator2 + pOAnnotator1 * pOAnnotator2;
        if (Math.abs(1.0 - pe) < 1e-12) {
            return po >= 1.0 ? 1.0 : 0.0;
        }
        return (po - pe) / (1.0 - pe);
    }

    private static LocalDate parseDateOr(final String value, final LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

}
