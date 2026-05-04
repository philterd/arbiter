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
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class ReportingController {

    private static final List<String> KNOWN_STATUSES = List.of(
            "PENDING", "REVIEW_REQUIRED", "AUDIT_REQUIRED", "AUTO_APPROVED", "APPROVED", "REJECTED", "FAILED");
    private static final Set<String> USER_DECIDED = Set.of("APPROVED", "REJECTED", "FAILED");

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final UserGroupsService userGroupsService;

    public ReportingController(BatchRepository batchRepository,
                               DocumentRepository documentRepository,
                               UserGroupsService userGroupsService) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.userGroupsService = userGroupsService;
    }

    @GetMapping("/reporting")
    public String view(@RequestParam(name = "myGroupsOnly", defaultValue = "true") boolean myGroupsOnly,
                       Authentication authentication,
                       Model model) {
        boolean admin = isAdmin(authentication);
        boolean restrict = !admin || myGroupsOnly;
        Set<String> myGroupIds = restrict
                ? userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName())
                : Set.of();

        List<Batch> batches = batchRepository.findAll();
        if (restrict) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }

        Map<String, Long> globalStatusCounts = new LinkedHashMap<>();
        for (String s : KNOWN_STATUSES) globalStatusCounts.put(s, 0L);

        long totalDocuments = 0;
        long autoApprovedTotal = 0;
        long needsReviewTotal = 0;
        double riskScoreSum = 0.0;
        long openBatches = 0;
        long closedBatches = 0;

        List<Map<String, Object>> batchRows = new ArrayList<>();
        for (Batch batch : batches) {
            if (batch.isClosed()) closedBatches++;
            else openBatches++;

            List<Document> docs = documentRepository.findByBatchId(batch.getId());
            Map<String, Long> statusCounts = new LinkedHashMap<>();
            for (String s : KNOWN_STATUSES) statusCounts.put(s, 0L);

            long batchAutoApproved = 0;
            double batchRiskSum = 0.0;
            for (Document d : docs) {
                String status = d.getStatus() == null ? "" : d.getStatus();
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

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("name", batch.getName() == null ? "" : batch.getName());
            row.put("closed", batch.isClosed());
            row.put("documentCount", (long) docs.size());
            row.put("statusCounts", statusCounts);
            row.put("autoApproved", batchAutoApproved);
            row.put("avgRiskScore", docs.isEmpty() ? 0.0 : batchRiskSum / docs.size());
            batchRows.add(row);
        }

        batchRows.sort(Comparator
                .comparingLong((Map<String, Object> r) -> ((Number) r.get("documentCount")).longValue())
                .reversed()
                .thenComparing(r -> ((String) r.get("name")).toLowerCase()));

        model.addAttribute("totalBatches", (long) batches.size());
        model.addAttribute("openBatches", openBatches);
        model.addAttribute("closedBatches", closedBatches);
        model.addAttribute("totalDocuments", totalDocuments);
        model.addAttribute("statusCounts", globalStatusCounts);
        model.addAttribute("autoApprovedTotal", autoApprovedTotal);
        model.addAttribute("needsReviewTotal", needsReviewTotal);
        model.addAttribute("avgRiskScore", totalDocuments == 0 ? 0.0 : riskScoreSum / totalDocuments);
        model.addAttribute("batchRows", batchRows);
        model.addAttribute("knownStatuses", KNOWN_STATUSES);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("myGroupsOnly", myGroupsOnly);
        return "reporting";
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
