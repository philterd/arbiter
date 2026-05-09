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
import ai.philterd.arbiter.model.ComplianceProfile;
import ai.philterd.arbiter.model.Domains;
import ai.philterd.arbiter.model.Group;
import ai.philterd.arbiter.model.PhilterDefaults;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.PiiWeights;
import ai.philterd.arbiter.model.WeightSet;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.GroupRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.BatchAccessService;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
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
    private final BatchAccessService batchAccessService;
    private final AuditLogService auditLogService;
    private final WeightSetRepository weightSetRepository;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final PhilterDefaultsService philterDefaultsService;
    private final ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    private final ComplianceProfileRepository complianceProfileRepository;

    public BatchController(final BatchRepository batchRepository,
                           final DocumentRepository documentRepository,
                           final GroupRepository groupRepository,
                           final UserGroupsService userGroupsService,
                           final BatchAccessService batchAccessService,
                           final AuditLogService auditLogService,
                           final WeightSetRepository weightSetRepository,
                           final PhilterInstanceRepository philterInstanceRepository,
                           final PhilterDefaultsService philterDefaultsService,
                           final ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository,
                           final ComplianceProfileRepository complianceProfileRepository) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.groupRepository = groupRepository;
        this.userGroupsService = userGroupsService;
        this.batchAccessService = batchAccessService;
        this.auditLogService = auditLogService;
        this.weightSetRepository = weightSetRepository;
        this.philterInstanceRepository = philterInstanceRepository;
        this.philterDefaultsService = philterDefaultsService;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
        this.complianceProfileRepository = complianceProfileRepository;
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "createdAt", "documentCount");
    private static final Set<String> TERMINAL_STATUSES = Set.of("APPROVED", "REJECTED", "FAILED", "FINALIZED");
    private static final Set<String> CLOSEABLE_STATUSES = Set.of("REJECTED", "FINALIZED");
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("REVIEW_REQUIRED", "AUDIT_REQUIRED");

    @GetMapping
    public String list(@RequestParam(name = "sort", defaultValue = "createdAt") final String sort,
                       @RequestParam(name = "dir", defaultValue = "desc") final String dir,
                       final Authentication authentication,
                       final Model model) {
        final boolean admin = AuthUtils.isAdmin(authentication);
        final boolean restrict = !admin;
        final Set<String> myGroupIds = restrict
                ? userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName())
                : Set.of();

        final List<Batch> batches = new ArrayList<>(batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent());
        if (restrict) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }

        final List<Group> allGroups = groupRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        final Map<String, String> groupNamesById = new LinkedHashMap<>();
        for (Group g : allGroups) {
            groupNamesById.put(g.getId(), g.getName());
        }

        final List<Group> assignableGroups = new ArrayList<>(allGroups);
        if (!admin) {
            assignableGroups.removeIf(g -> !myGroupIds.contains(g.getId()));
        }
        assignableGroups.sort(Comparator.comparing(
                (Group g) -> g.getName() == null ? "" : g.getName().toLowerCase()));

        final Map<String, String> philterInstanceNamesById = new LinkedHashMap<>();
        for (PhilterInstance i : philterInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            philterInstanceNamesById.put(i.getId(), i.getName());
        }

        final Map<String, String> finalizationPolicyNamesById = new LinkedHashMap<>();
        for (ai.philterd.arbiter.model.FinalizationPolicy p : finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            finalizationPolicyNamesById.put(p.getId(), p.getName());
        }

        final Map<String, String> complianceProfileNamesById = new LinkedHashMap<>();
        for (ComplianceProfile cp : complianceProfileRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            complianceProfileNamesById.put(cp.getId(), cp.getName());
        }

        final PageRequest firstByFilename = PageRequest.of(0, 1, Sort.by("filename", "id"));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Batch batch : batches) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("name", batch.getName());
            row.put("description", batch.getDescription());
            row.put("createdAt", batch.getCreatedAt());
            row.put("confidenceThreshold", batch.getConfidenceThreshold());
            row.put("documentThreshold", batch.getDocumentThreshold());
            row.put("auditSamplingRate", batch.getAuditSamplingRate());
            final long total = documentRepository.countByBatchId(batch.getId());
            final long terminal = documentRepository.countByBatchIdAndStatusIn(batch.getId(), TERMINAL_STATUSES);
            row.put("documentCount", total);
            row.put("queuedCount", total - terminal);
            row.put("groupId", batch.getGroupId());
            row.put("groupName", batch.getGroupId() == null ? null : groupNamesById.get(batch.getGroupId()));
            row.put("philterInstanceId", batch.getPhilterInstanceId());
            final String philterName = batch.getPhilterInstanceId() == null
                    ? "Embedded Philter"
                    : philterInstanceNamesById.getOrDefault(batch.getPhilterInstanceId(), "(missing)");
            row.put("philterInstanceName", philterName);
            row.put("policyName", batch.getPolicyName());
            row.put("domain", batch.getDomain());
            row.put("context", batch.getContext());
            row.put("finalizationPolicyId", batch.getFinalizationPolicyId());
            row.put("finalizationPolicyName", batch.getFinalizationPolicyId() == null
                    ? null : finalizationPolicyNamesById.get(batch.getFinalizationPolicyId()));
            row.put("complianceProfileId", batch.getComplianceProfileId());
            row.put("complianceProfileName", batch.getComplianceProfileId() == null
                    ? null : complianceProfileNamesById.get(batch.getComplianceProfileId()));
            row.put("closed", batch.isClosed());
            row.put("closedAt", batch.getClosedAt());
            String firstUnreviewedId = null;
            if (!batch.isClosed()) {
                final List<Document> unreviewed = documentRepository
                        .findByBatchIdAndStatusIn(batch.getId(), REVIEWABLE_STATUSES, firstByFilename)
                        .getContent();
                if (!unreviewed.isEmpty()) {
                    firstUnreviewedId = unreviewed.get(0).getId();
                }
            }
            row.put("firstUnreviewedDocumentId", firstUnreviewedId);
            rows.add(row);
        }

        final String activeSort = SORTABLE_FIELDS.contains(sort) ? sort : "createdAt";
        final boolean ascending = "asc".equalsIgnoreCase(dir);
        rows.sort(comparatorFor(activeSort, ascending));

        final List<PhilterInstance> philterInstances = new ArrayList<>(philterInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent());

        final PhilterDefaults philterDefaults = philterDefaultsService.load();

        // Build the finalization-policy dropdown — id + name, sorted by name. Required
        // for new batches; legacy batches without one display a "—" until edited.
        final List<ai.philterd.arbiter.model.FinalizationPolicy> finalizationPolicies =
                finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();

        final List<ComplianceProfile> complianceProfiles = complianceProfileRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()
                .stream().filter(cp -> !cp.isArchived()).collect(java.util.stream.Collectors.toList());

        model.addAttribute("batches", rows);
        model.addAttribute("groups", assignableGroups);
        model.addAttribute("philterInstances", philterInstances);
        model.addAttribute("defaultPhilterInstanceId", philterDefaults.getDefaultInstanceId());
        model.addAttribute("domains", Domains.VALUES);
        model.addAttribute("finalizationPolicies", finalizationPolicies);
        model.addAttribute("complianceProfiles", complianceProfiles);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("currentSort", activeSort);
        model.addAttribute("currentDir", ascending ? "asc" : "desc");
        return "batches";
    }

    private static Comparator<Map<String, Object>> comparatorFor(final String field, final boolean ascending) {
        final Comparator<Map<String, Object>> base = switch (field) {
            case "name" -> Comparator.comparing(
                    r -> r.get("name") == null ? "" : ((String) r.get("name")).toLowerCase(),
                    Comparator.naturalOrder());
            case "documentCount" -> Comparator.comparingLong(
                    r -> {
                        final Object v = r.get("documentCount");
                        return v instanceof Number ? ((Number) v).longValue() : 0L;
                    });
            default -> Comparator.comparing(
                    r -> r.get("createdAt") == null ? LocalDateTime.MIN : (LocalDateTime) r.get("createdAt"));
        };
        return ascending ? base : base.reversed();
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         @RequestParam(value = "description", required = false) String description,
                         @RequestParam(value = "confidenceThreshold", required = false) Double confidenceThreshold,
                         @RequestParam(value = "documentThreshold", required = false) Double documentThreshold,
                         @RequestParam("groupId") String groupId,
                         @RequestParam(value = "philterInstanceId", required = false) String philterInstanceId,
                         @RequestParam(value = "policyName", required = false) String policyName,
                         @RequestParam(value = "domain", required = false) String domain,
                         @RequestParam(value = "context", required = false, defaultValue = "") String context,
                         @RequestParam(value = "finalizationPolicyId", required = false) String finalizationPolicyId,
                         @RequestParam(value = "complianceProfileId", required = false) String complianceProfileId,
                         @RequestParam(value = "exemptionCodeRequired", required = false) Boolean exemptionCodeRequired,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can create batches.");
            return "redirect:/batches";
        }
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Batch name is required.");
            return "redirect:/batches";
        }
        final Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (confidenceThreshold != null && normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (documentThreshold != null && normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        final String trimmedPhilterId = philterInstanceId == null ? "" : philterInstanceId.trim();
        if (!trimmedPhilterId.isEmpty() && !philterInstanceRepository.existsById(trimmedPhilterId)) {
            redirectAttributes.addFlashAttribute("error", "Selected Philter instance no longer exists.");
            return "redirect:/batches";
        }
        final String trimmedDomain = domain == null ? "" : domain.trim();
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
        final String trimmedFinalization = finalizationPolicyId == null ? "" : finalizationPolicyId.trim();
        if (trimmedFinalization.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A finalization policy must be selected.");
            return "redirect:/batches";
        }
        if (!finalizationPolicyRepository.existsById(trimmedFinalization)) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected finalization policy no longer exists.");
            return "redirect:/batches";
        }
        final String trimmedComplianceId = complianceProfileId == null ? "" : complianceProfileId.trim();
        if (trimmedComplianceId.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A compliance profile must be selected.");
            return "redirect:/batches";
        }
        if (!complianceProfileRepository.existsById(trimmedComplianceId)) {
            redirectAttributes.addFlashAttribute("error", "Selected compliance profile no longer exists.");
            return "redirect:/batches";
        }
        final Batch batch = new Batch();
        batch.setId(UUID.randomUUID().toString());
        batch.setName(trimmed);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setOwnerId(authentication == null ? "unknown" : authentication.getName());
        batch.setGroupId(groupId);
        batch.setPhilterInstanceId(trimmedPhilterId.isEmpty() ? null : trimmedPhilterId);
        final String trimmedPolicy = policyName == null ? "" : policyName.trim();
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batch.setDomain(trimmedDomain);
        batch.setDescription(description == null ? "" : description.trim());
        batch.setContext(context == null ? "" : context);
        batch.setFinalizationPolicyId(trimmedFinalization);
        batch.setComplianceProfileId(trimmedComplianceId);
        // Form checkboxes only submit a value when checked; an unchecked box arrives as null,
        // which we interpret as "exemption code not required". Default for missing field is true.
        batch.setExemptionCodeRequired(exemptionCodeRequired != null && exemptionCodeRequired);
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
                        "contextLength", batch.getContext().length(),
                        "confidenceThreshold", batch.getConfidenceThreshold(),
                        "documentThreshold", batch.getDocumentThreshold(),
                        "complianceProfileId", trimmedComplianceId,
                        "exemptionCodeRequired", batch.isExemptionCodeRequired()));
        redirectAttributes.addFlashAttribute("success", "Batch \"" + trimmed + "\" created.");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/philter")
    public String changePhilter(@PathVariable final String batchId,
                                @RequestParam(value = "philterInstanceId", required = false) final String philterInstanceId,
                                @RequestParam(value = "policyName", required = false) final String policyName,
                                final Authentication authentication,
                                final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final String trimmed = philterInstanceId == null ? "" : philterInstanceId.trim();
        if (!trimmed.isEmpty() && !philterInstanceRepository.existsById(trimmed)) {
            redirectAttributes.addFlashAttribute("error", "Selected Philter instance no longer exists.");
            return "redirect:/batches";
        }
        final String trimmedPolicy = policyName == null ? "" : policyName.trim();

        final String previousId = batch.getPhilterInstanceId();
        final String previousPolicy = batch.getPolicyName();
        batch.setPhilterInstanceId(trimmed.isEmpty() ? null : trimmed);
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batchRepository.save(batch);
        auditLogService.log("BATCH_PHILTER_CHANGE", "Batch", batch.getId(),
                Map.of("previousPhilterInstanceId", previousId == null ? "embedded" : previousId,
                        "newPhilterInstanceId", trimmed.isEmpty() ? "embedded" : trimmed,
                        "previousPolicyName", previousPolicy == null ? "" : previousPolicy,
                        "newPolicyName", trimmedPolicy));
        final String instanceName = trimmed.isEmpty() ? "Embedded Philter"
                : philterInstanceRepository.findById(trimmed)
                .map(PhilterInstance::getName).orElse(trimmed);
        final String policyLabel = trimmedPolicy.isEmpty() ? "no policy" : "policy \"" + trimmedPolicy + "\"";
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" now uses \"" + instanceName
                        + "\" with " + policyLabel + ".");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/group")
    public String changeGroup(@PathVariable final String batchId,
                              @RequestParam("groupId") final String groupId,
                              final Authentication authentication,
                              final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        final String previousGroupId = batch.getGroupId();
        batch.setGroupId(groupId);
        batchRepository.save(batch);
        auditLogService.log("BATCH_GROUP_CHANGE", "Batch", batch.getId(),
                Map.of("previousGroupId", previousGroupId == null ? "" : previousGroupId,
                        "newGroupId", groupId));
        final String groupName = groupRepository.findById(groupId).map(Group::getName).orElse(groupId);
        redirectAttributes.addFlashAttribute(
                "success",
                "Batch \"" + batch.getName() + "\" assigned to group \"" + groupName + "\".");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/domain")
    public String changeDomain(@PathVariable final String batchId,
                               @RequestParam("domain") final String domain,
                               final Authentication authentication,
                               final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final String trimmed = domain == null ? "" : domain.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Domain is required.");
            return "redirect:/batches";
        }
        if (!Domains.isValid(trimmed)) {
            redirectAttributes.addFlashAttribute("error",
                    "Domain \"" + trimmed + "\" is not a valid choice.");
            return "redirect:/batches";
        }
        final String previous = batch.getDomain();
        batch.setDomain(trimmed);
        batchRepository.save(batch);
        auditLogService.log("BATCH_DOMAIN_CHANGE", "Batch", batch.getId(),
                Map.of("previousDomain", previous == null ? "" : previous,
                        "newDomain", trimmed));
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" domain set to \"" + trimmed + "\".");
        return "redirect:/batches";
    }

    @GetMapping("/{batchId}/weights")
    public String weights(@PathVariable final String batchId,
                          final Authentication authentication,
                          final Model model,
                          final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final Map<String, Integer> effective = PiiWeights.effective(batch.getPiiTypeWeights());
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : effective.entrySet()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("label", PiiTypes.labelFor(entry.getKey()));
            row.put("weight", entry.getValue());
            row.put("defaultWeight", PiiWeights.weightFor(entry.getKey(), null));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> ((String) r.get("label")).toLowerCase()));

        final List<WeightSet> weightSets = weightSetRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        final List<Map<String, Object>> weightSetData = new ArrayList<>();
        for (WeightSet ws : weightSets) {
            final Map<String, Object> entry = new LinkedHashMap<>();
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
    public String saveWeights(@PathVariable final String batchId,
                              @RequestParam(value = "type", required = false) final List<String> types,
                              @RequestParam(value = "weight", required = false) final List<Integer> weights,
                              @RequestParam(value = "reset", required = false) final String reset,
                              @RequestParam(value = "weightSetId", required = false) final String weightSetId,
                              final Authentication authentication,
                              final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
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

        final Map<String, Integer> overrides = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            final String type = types.get(i);
            final Integer weight = weights.get(i);
            if (type == null || weight == null) continue;
            final String key = type.trim().toLowerCase();
            if (!PiiTypes.isValid(key)) continue;
            if (weight < 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Weight for " + PiiTypes.labelFor(key) + " must be 0 or greater.");
                return "redirect:/batches/" + batchId + "/weights";
            }
            final int defaultWeight = PiiWeights.weightFor(key, null);
            if (weight != defaultWeight) {
                overrides.put(key, weight);
            }
        }

        batch.setPiiTypeWeights(overrides.isEmpty() ? null : overrides);
        // Update the binding to whichever preset was last loaded (or null if none).
        final String trimmedSetId = weightSetId == null ? null : weightSetId.trim();
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
    public String editThresholds(@PathVariable final String batchId,
                                 @RequestParam("confidenceThreshold") final double confidenceThreshold,
                                 @RequestParam("documentThreshold") final double documentThreshold,
                                 @RequestParam(value = "auditSamplingRate", required = false) final Double auditSamplingRate,
                                 @RequestParam(value = "context", required = false) final String context,
                                 @RequestParam(value = "domain", required = false) final String domain,
                                 @RequestParam(value = "description", required = false) final String description,
                                 final Authentication authentication,
                                 final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can modify batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedRate = auditSamplingRate == null ? batch.getAuditSamplingRate() : normalizeThreshold(auditSamplingRate);
        if (normalizedRate == null) {
            redirectAttributes.addFlashAttribute("error", "Audit sampling rate must be between 0 and 1.");
            return "redirect:/batches";
        }
        // Domain is optional on this endpoint — only validated/applied when sent.
        if (domain != null) {
            final String trimmedDomain = domain.trim();
            if (trimmedDomain.isEmpty() || !Domains.isValid(trimmedDomain)) {
                redirectAttributes.addFlashAttribute("error",
                        "Domain \"" + trimmedDomain + "\" is not a valid choice.");
                return "redirect:/batches";
            }
        }
        final double previousPii = batch.getConfidenceThreshold();
        final double previousDoc = batch.getDocumentThreshold();
        final double previousRate = batch.getAuditSamplingRate();
        final String previousContext = batch.getContext();
        final String previousDomain = batch.getDomain();
        batch.setConfidenceThreshold(normalizedPii);
        batch.setDocumentThreshold(normalizedDoc);
        batch.setAuditSamplingRate(normalizedRate);
        // context, domain, and description are optional in the form — only update when explicitly sent.
        if (context != null) {
            batch.setContext(context);
        }
        if (domain != null) {
            batch.setDomain(domain.trim());
        }
        if (description != null) {
            batch.setDescription(description.trim());
        }
        batchRepository.save(batch);
        final boolean contextChanged = context != null && !batch.getContext().equals(previousContext);
        final boolean domainChanged = domain != null
                && !java.util.Objects.equals(batch.getDomain(), previousDomain);
        auditLogService.log("BATCH_THRESHOLDS_CHANGE", "Batch", batch.getId(),
                Map.of(
                        "previousPii", previousPii, "currentPii", normalizedPii,
                        "previousDocument", previousDoc, "currentDocument", normalizedDoc,
                        "previousAuditSamplingRate", previousRate, "currentAuditSamplingRate", normalizedRate,
                        "contextChanged", contextChanged,
                        "domainChanged", domainChanged));
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
    public String close(@PathVariable final String batchId,
                        final Authentication authentication,
                        final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can close batches.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error", "Batch is already closed.");
            return "redirect:/batches";
        }
        final long total = documentRepository.countByBatchId(batchId);
        final long ready = documentRepository.countByBatchIdAndStatusIn(batchId, CLOSEABLE_STATUSES);
        if (total != ready) {
            final long pending = total - ready;
            redirectAttributes.addFlashAttribute("error",
                    "Batch \"" + batch.getName() + "\" cannot be closed: " + pending
                            + (pending == 1 ? " document is" : " documents are")
                            + " not yet rejected or finalized.");
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

    private Set<String> userGroupIds(final Authentication auth) {
        return userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
    }
}
