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
import ai.philterd.arbiter.model.Domains;
import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.PhilterDefaults;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.PiiWeights;
import ai.philterd.arbiter.model.WeightSet;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Controller
@RequestMapping("/batches")
public class BatchController {

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final GroupRepository groupRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final WeightSetRepository weightSetRepository;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final PhilterDefaultsService philterDefaultsService;

    public BatchController(BatchRepository batchRepository,
                           DocumentRepository documentRepository,
                           GroupRepository groupRepository,
                           UserGroupsService userGroupsService,
                           AuditLogService auditLogService,
                           WeightSetRepository weightSetRepository,
                           PhilterInstanceRepository philterInstanceRepository,
                           PhilterDefaultsService philterDefaultsService) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.groupRepository = groupRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.weightSetRepository = weightSetRepository;
        this.philterInstanceRepository = philterInstanceRepository;
        this.philterDefaultsService = philterDefaultsService;
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "createdAt", "documentCount");
    private static final Set<String> TERMINAL_STATUSES = Set.of("APPROVED", "REJECTED", "FAILED");
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("REVIEW_REQUIRED", "AUDIT_REQUIRED");

    @GetMapping
    public String list(@RequestParam(name = "myGroupsOnly", defaultValue = "true") boolean myGroupsOnly,
                       @RequestParam(name = "sort", defaultValue = "createdAt") String sort,
                       @RequestParam(name = "dir", defaultValue = "desc") String dir,
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

        List<Group> allGroups = groupRepository.findAll();
        Map<String, String> groupNamesById = new LinkedHashMap<>();
        for (Group g : allGroups) {
            groupNamesById.put(g.getId(), g.getName());
        }

        List<Group> assignableGroups = new ArrayList<>(allGroups);
        if (!admin) {
            assignableGroups.removeIf(g -> !myGroupIds.contains(g.getId()));
        }
        assignableGroups.sort(Comparator.comparing(
                (Group g) -> g.getName() == null ? "" : g.getName().toLowerCase()));

        Map<String, String> philterInstanceNamesById = new LinkedHashMap<>();
        for (PhilterInstance i : philterInstanceRepository.findAll()) {
            philterInstanceNamesById.put(i.getId(), i.getName());
        }

        PageRequest firstByFilename = PageRequest.of(0, 1, Sort.by("filename", "id"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Batch batch : batches) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("name", batch.getName());
            row.put("createdAt", batch.getCreatedAt());
            row.put("confidenceThreshold", batch.getConfidenceThreshold());
            row.put("documentThreshold", batch.getDocumentThreshold());
            row.put("auditSamplingRate", batch.getAuditSamplingRate());
            long total = documentRepository.countByBatchId(batch.getId());
            long terminal = documentRepository.countByBatchIdAndStatusIn(batch.getId(), TERMINAL_STATUSES);
            row.put("documentCount", total);
            row.put("queuedCount", total - terminal);
            row.put("groupId", batch.getGroupId());
            row.put("groupName", batch.getGroupId() == null ? null : groupNamesById.get(batch.getGroupId()));
            row.put("philterInstanceId", batch.getPhilterInstanceId());
            String philterName = batch.getPhilterInstanceId() == null
                    ? "Embedded Philter"
                    : philterInstanceNamesById.getOrDefault(batch.getPhilterInstanceId(), "(missing)");
            row.put("philterInstanceName", philterName);
            row.put("policyName", batch.getPolicyName());
            row.put("domain", batch.getDomain());
            row.put("closed", batch.isClosed());
            row.put("closedAt", batch.getClosedAt());
            String firstUnreviewedId = null;
            if (!batch.isClosed()) {
                List<Document> unreviewed = documentRepository
                        .findByBatchIdAndStatusIn(batch.getId(), REVIEWABLE_STATUSES, firstByFilename)
                        .getContent();
                if (!unreviewed.isEmpty()) {
                    firstUnreviewedId = unreviewed.get(0).getId();
                }
            }
            row.put("firstUnreviewedDocumentId", firstUnreviewedId);
            rows.add(row);
        }

        String activeSort = SORTABLE_FIELDS.contains(sort) ? sort : "createdAt";
        boolean ascending = "asc".equalsIgnoreCase(dir);
        rows.sort(comparatorFor(activeSort, ascending));

        List<PhilterInstance> philterInstances = new ArrayList<>(philterInstanceRepository.findAll());
        philterInstances.sort(Comparator.comparing(
                (PhilterInstance i) -> i.getName() == null ? "" : i.getName().toLowerCase()));

        PhilterDefaults philterDefaults = philterDefaultsService.load();

        model.addAttribute("batches", rows);
        model.addAttribute("groups", assignableGroups);
        model.addAttribute("philterInstances", philterInstances);
        model.addAttribute("defaultPhilterInstanceId", philterDefaults.getDefaultInstanceId());
        model.addAttribute("domains", Domains.VALUES);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("myGroupsOnly", myGroupsOnly);
        model.addAttribute("currentSort", activeSort);
        model.addAttribute("currentDir", ascending ? "asc" : "desc");
        return "batches";
    }

    private static Comparator<Map<String, Object>> comparatorFor(String field, boolean ascending) {
        Comparator<Map<String, Object>> base = switch (field) {
            case "name" -> Comparator.comparing(
                    r -> r.get("name") == null ? "" : ((String) r.get("name")).toLowerCase(),
                    Comparator.naturalOrder());
            case "documentCount" -> Comparator.comparingLong(
                    r -> {
                        Object v = r.get("documentCount");
                        return v instanceof Number ? ((Number) v).longValue() : 0L;
                    });
            default -> Comparator.comparing(
                    r -> r.get("createdAt") == null ? LocalDateTime.MIN : (LocalDateTime) r.get("createdAt"));
        };
        return ascending ? base : base.reversed();
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         @RequestParam(value = "confidenceThreshold", required = false) Double confidenceThreshold,
                         @RequestParam(value = "documentThreshold", required = false) Double documentThreshold,
                         @RequestParam("groupId") String groupId,
                         @RequestParam(value = "philterInstanceId", required = false) String philterInstanceId,
                         @RequestParam(value = "policyName", required = false) String policyName,
                         @RequestParam(value = "domain", required = false) String domain,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can create batches.");
            return "redirect:/batches";
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Batch name is required.");
            return "redirect:/batches";
        }
        Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (confidenceThreshold != null && normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (documentThreshold != null && normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        String trimmedPhilterId = philterInstanceId == null ? "" : philterInstanceId.trim();
        if (!trimmedPhilterId.isEmpty() && !philterInstanceRepository.existsById(trimmedPhilterId)) {
            redirectAttributes.addFlashAttribute("error", "Selected Philter instance no longer exists.");
            return "redirect:/batches";
        }
        String trimmedDomain = domain == null ? "" : domain.trim();
        if (trimmedDomain.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Domain is required.");
            return "redirect:/batches";
        }
        if (!Domains.isValid(trimmedDomain)) {
            redirectAttributes.addFlashAttribute("error", "Domain \"" + trimmedDomain + "\" is not a valid choice.");
            return "redirect:/batches";
        }
        if (batchRepository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A batch named \"" + trimmed + "\" already exists.");
            return "redirect:/batches";
        }
        Batch batch = new Batch();
        batch.setId(UUID.randomUUID().toString());
        batch.setName(trimmed);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setOwnerId(authentication == null ? "unknown" : authentication.getName());
        batch.setGroupId(groupId);
        batch.setPhilterInstanceId(trimmedPhilterId.isEmpty() ? null : trimmedPhilterId);
        String trimmedPolicy = policyName == null ? "" : policyName.trim();
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batch.setDomain(trimmedDomain);
        if (normalizedPii != null) {
            batch.setConfidenceThreshold(normalizedPii);
        }
        if (normalizedDoc != null) {
            batch.setDocumentThreshold(normalizedDoc);
        }
        try {
            batchRepository.save(batch);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Race against another admin creating the same name concurrently.
            redirectAttributes.addFlashAttribute("error",
                    "A batch named \"" + trimmed + "\" already exists.");
            return "redirect:/batches";
        }
        auditLogService.log("BATCH_CREATE", "Batch", batch.getId(),
                Map.of("name", trimmed, "groupId", groupId,
                        "philterInstanceId", trimmedPhilterId.isEmpty() ? "embedded" : trimmedPhilterId,
                        "policyName", trimmedPolicy,
                        "domain", trimmedDomain,
                        "confidenceThreshold", batch.getConfidenceThreshold(),
                        "documentThreshold", batch.getDocumentThreshold()));
        redirectAttributes.addFlashAttribute("success", "Batch \"" + trimmed + "\" created.");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/philter")
    public String changePhilter(@PathVariable String batchId,
                                @RequestParam(value = "philterInstanceId", required = false) String philterInstanceId,
                                @RequestParam(value = "policyName", required = false) String policyName,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        String trimmed = philterInstanceId == null ? "" : philterInstanceId.trim();
        if (!trimmed.isEmpty() && !philterInstanceRepository.existsById(trimmed)) {
            redirectAttributes.addFlashAttribute("error", "Selected Philter instance no longer exists.");
            return "redirect:/batches";
        }
        String trimmedPolicy = policyName == null ? "" : policyName.trim();

        String previousId = batch.getPhilterInstanceId();
        String previousPolicy = batch.getPolicyName();
        batch.setPhilterInstanceId(trimmed.isEmpty() ? null : trimmed);
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batchRepository.save(batch);
        auditLogService.log("BATCH_PHILTER_CHANGE", "Batch", batch.getId(),
                Map.of("previousPhilterInstanceId", previousId == null ? "embedded" : previousId,
                        "newPhilterInstanceId", trimmed.isEmpty() ? "embedded" : trimmed,
                        "previousPolicyName", previousPolicy == null ? "" : previousPolicy,
                        "newPolicyName", trimmedPolicy));
        String instanceName = trimmed.isEmpty() ? "Embedded Philter"
                : philterInstanceRepository.findById(trimmed)
                        .map(PhilterInstance::getName).orElse(trimmed);
        String policyLabel = trimmedPolicy.isEmpty() ? "no policy" : "policy \"" + trimmedPolicy + "\"";
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" now uses \"" + instanceName
                        + "\" with " + policyLabel + ".");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/group")
    public String changeGroup(@PathVariable String batchId,
                              @RequestParam("groupId") String groupId,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        String previousGroupId = batch.getGroupId();
        batch.setGroupId(groupId);
        batchRepository.save(batch);
        auditLogService.log("BATCH_GROUP_CHANGE", "Batch", batch.getId(),
                Map.of("previousGroupId", previousGroupId == null ? "" : previousGroupId,
                        "newGroupId", groupId));
        String groupName = groupRepository.findById(groupId).map(Group::getName).orElse(groupId);
        redirectAttributes.addFlashAttribute(
                "success",
                "Batch \"" + batch.getName() + "\" assigned to group \"" + groupName + "\".");
        return "redirect:/batches";
    }

    @GetMapping("/{batchId}/weights")
    public String weights(@PathVariable String batchId,
                          Authentication authentication,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        Map<String, Integer> effective = PiiWeights.effective(batch.getPiiTypeWeights());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : effective.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("label", PiiTypes.labelFor(entry.getKey()));
            row.put("weight", entry.getValue());
            row.put("defaultWeight", PiiWeights.weightFor(entry.getKey(), null));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> ((String) r.get("label")).toLowerCase()));

        List<WeightSet> weightSets = weightSetRepository.findAll();
        weightSets.sort(Comparator.comparing(
                (WeightSet w) -> w.getName() == null ? "" : w.getName().toLowerCase()));
        List<Map<String, Object>> weightSetData = new ArrayList<>();
        for (WeightSet ws : weightSets) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ws.getId());
            entry.put("name", ws.getName());
            entry.put("weights", ws.getWeights() == null ? Map.of() : ws.getWeights());
            weightSetData.add(entry);
        }

        model.addAttribute("batch", batch);
        model.addAttribute("weightRows", rows);
        model.addAttribute("weightSets", weightSetData);
        model.addAttribute("currentWeightSetId", batch.getWeightSetId());
        return "batch-weights";
    }

    @PostMapping("/{batchId}/weights")
    public String saveWeights(@PathVariable String batchId,
                              @RequestParam(value = "type", required = false) List<String> types,
                              @RequestParam(value = "weight", required = false) List<Integer> weights,
                              @RequestParam(value = "reset", required = false) String reset,
                              @RequestParam(value = "weightSetId", required = false) String weightSetId,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }

        if (reset != null) {
            batch.setPiiTypeWeights(null);
            batch.setWeightSetId(null);
            batchRepository.save(batch);
            auditLogService.log("BATCH_WEIGHTS_RESET", "Batch", batch.getId(), null);
            redirectAttributes.addFlashAttribute("success",
                    "Weights for \"" + batch.getName() + "\" reset to defaults.");
            return "redirect:/batches/" + batchId + "/weights";
        }

        if (types == null || weights == null || types.size() != weights.size()) {
            redirectAttributes.addFlashAttribute("error", "Invalid weights submission.");
            return "redirect:/batches/" + batchId + "/weights";
        }

        Map<String, Integer> overrides = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            Integer weight = weights.get(i);
            if (type == null || weight == null) continue;
            String key = type.trim().toLowerCase();
            if (!PiiTypes.isValid(key)) continue;
            if (weight < 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Weight for " + PiiTypes.labelFor(key) + " must be 0 or greater.");
                return "redirect:/batches/" + batchId + "/weights";
            }
            int defaultWeight = PiiWeights.weightFor(key, null);
            if (weight != defaultWeight) {
                overrides.put(key, weight);
            }
        }

        batch.setPiiTypeWeights(overrides.isEmpty() ? null : overrides);
        // Update the binding to whichever preset was last loaded (or null if none).
        String trimmedSetId = weightSetId == null ? null : weightSetId.trim();
        if (trimmedSetId == null || trimmedSetId.isEmpty()) {
            batch.setWeightSetId(null);
        } else if (weightSetRepository.existsById(trimmedSetId)) {
            batch.setWeightSetId(trimmedSetId);
        } else {
            batch.setWeightSetId(null);
        }
        batchRepository.save(batch);
        auditLogService.log("BATCH_WEIGHTS_CHANGE", "Batch", batch.getId(),
                Map.of("overrideCount", overrides.size(), "overrides", overrides,
                        "weightSetId", batch.getWeightSetId() == null ? "" : batch.getWeightSetId()));
        redirectAttributes.addFlashAttribute("success",
                "Weights for \"" + batch.getName() + "\" updated.");
        return "redirect:/batches/" + batchId + "/weights";
    }

    @PostMapping("/{batchId}/thresholds")
    public String editThresholds(@PathVariable String batchId,
                                 @RequestParam("confidenceThreshold") double confidenceThreshold,
                                 @RequestParam("documentThreshold") double documentThreshold,
                                 @RequestParam(value = "auditSamplingRate", required = false) Double auditSamplingRate,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        Double normalizedRate = auditSamplingRate == null ? batch.getAuditSamplingRate() : normalizeThreshold(auditSamplingRate);
        if (normalizedRate == null) {
            redirectAttributes.addFlashAttribute("error", "Audit sampling rate must be between 0 and 1.");
            return "redirect:/batches";
        }
        double previousPii = batch.getConfidenceThreshold();
        double previousDoc = batch.getDocumentThreshold();
        double previousRate = batch.getAuditSamplingRate();
        batch.setConfidenceThreshold(normalizedPii);
        batch.setDocumentThreshold(normalizedDoc);
        batch.setAuditSamplingRate(normalizedRate);
        batchRepository.save(batch);
        auditLogService.log("BATCH_THRESHOLDS_CHANGE", "Batch", batch.getId(),
                Map.of(
                        "previousPii", previousPii, "currentPii", normalizedPii,
                        "previousDocument", previousDoc, "currentDocument", normalizedDoc,
                        "previousAuditSamplingRate", previousRate, "currentAuditSamplingRate", normalizedRate));
        redirectAttributes.addFlashAttribute(
                "success",
                "Settings for \"" + batch.getName() + "\" updated.");
        return "redirect:/batches";
    }

    private static Double normalizeThreshold(Double value) {
        if (value == null || Double.isNaN(value) || value < 0.0 || value > 1.0) {
            return null;
        }
        return value;
    }

    @PostMapping("/{batchId}/close")
    public String close(@PathVariable String batchId,
                        Authentication authentication,
                        RedirectAttributes redirectAttributes) {
        if (!isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can close batches.");
            return "redirect:/batches";
        }
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error", "Batch is already closed.");
            return "redirect:/batches";
        }
        batch.setClosed(true);
        batch.setClosedAt(LocalDateTime.now());
        batch.setClosedBy(authentication == null ? "unknown" : authentication.getName());
        batchRepository.save(batch);
        auditLogService.log("BATCH_CLOSE", "Batch", batchId,
                Map.of("name", batch.getName() == null ? "" : batch.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" closed.");
        return "redirect:/batches";
    }

    private Set<String> userGroupIds(Authentication auth) {
        return userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
    }

    private boolean canAccessBatch(Authentication auth, Batch batch) {
        if (isAdmin(auth)) return true;
        return batch.getGroupId() != null && userGroupIds(auth).contains(batch.getGroupId());
    }
}
