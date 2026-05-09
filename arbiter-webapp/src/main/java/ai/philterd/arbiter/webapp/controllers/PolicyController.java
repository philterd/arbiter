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
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.model.Policy;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.GeneralSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.net.URI;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Controller
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);
    private static final String EMBEDDED = "embedded";
    private static final Pattern POLICY_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]{1,64}");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final PolicyRepository policyRepository;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final GeneralSettingsService generalSettingsService;
    private final ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public PolicyController(final PolicyRepository policyRepository,
                            final PhilterInstanceRepository philterInstanceRepository,
                            final BatchRepository batchRepository,
                            final AuditLogService auditLogService,
                            final ObjectMapper objectMapper,
                            final GeneralSettingsService generalSettingsService,
                            final ai.philterd.arbiter.repository.FinalizationPolicyRepository finalizationPolicyRepository) {
        this.policyRepository = policyRepository;
        this.philterInstanceRepository = philterInstanceRepository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.generalSettingsService = generalSettingsService;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
    }

    @GetMapping("/policies")
    public String list(@RequestParam(value = "instanceId", required = false) final String instanceId,
                       final Model model) {
        final org.springframework.data.domain.Page<PhilterInstance> instancesPage =
                philterInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<PhilterInstance> instances = instancesPage != null ? instancesPage.getContent() : List.of();

        final String selected = (instanceId == null || instanceId.isBlank()) ? EMBEDDED : instanceId;
        final boolean isEmbedded = EMBEDDED.equals(selected);

        final List<Map<String, Object>> rows = new ArrayList<>();
        String fetchError = null;
        String selectedInstanceName = "Embedded Philter";

        if (isEmbedded) {
            final org.springframework.data.domain.Page<Policy> policiesPage =
                    policyRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
            final List<Policy> policies = policiesPage != null ? policiesPage.getContent() : List.of();
            for (Policy p : policies) {
                final Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", p.getId());
                row.put("name", p.getName());
                rows.add(row);
            }
        } else {
            final Optional<PhilterInstance> instance = philterInstanceRepository.findById(selected);
            if (instance.isEmpty()) {
                fetchError = "Selected Philter instance no longer exists.";
            } else {
                selectedInstanceName = instance.get().getName();
                try {
                    for (String name : fetchRemotePolicyNames(instance.get())) {
                        final Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", name);
                        row.put("name", name);
                        rows.add(row);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch policies from {}: {}", selectedInstanceName, e.getMessage());
                    fetchError = "Could not reach Philter instance \""
                            + selectedInstanceName + "\": " + e.getMessage();
                }
            }
        }

        model.addAttribute("instances", instances);
        model.addAttribute("selectedInstanceId", selected);
        model.addAttribute("selectedInstanceName", selectedInstanceName);
        model.addAttribute("isEmbedded", isEmbedded);
        model.addAttribute("policies", rows);
        model.addAttribute("policyEditorUrl", policyEditorUrl());
        if (fetchError != null) {
            model.addAttribute("fetchError", fetchError);
        }

        // Finalization-policy tab data: list every policy plus per-policy "in use by N
        // batches" so the template can disable the Delete button when the policy is bound.
        final org.springframework.data.domain.Page<ai.philterd.arbiter.model.FinalizationPolicy> finalizationPoliciesPage =
                finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<ai.philterd.arbiter.model.FinalizationPolicy> finalizationPolicies =
                finalizationPoliciesPage != null ? finalizationPoliciesPage.getContent() : List.of();
        final List<Map<String, Object>> finalizationRows = new ArrayList<>();
        for (ai.philterd.arbiter.model.FinalizationPolicy p : finalizationPolicies) {
            final Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("option", p.getOption());
            row.put("optionLabel", ai.philterd.arbiter.model.FinalizationPolicy.labels()
                    .getOrDefault(p.getOption(), p.getOption()));
            row.put("deleteAfterDays", p.getDeleteAfterDays());
            row.put("inUse", batchRepository.existsByFinalizationPolicyId(p.getId()));
            finalizationRows.add(row);
        }
        model.addAttribute("finalizationPolicies", finalizationRows);
        model.addAttribute("finalizationOptions",
                ai.philterd.arbiter.model.FinalizationPolicy.labels());
        return "policies";
    }

    @PostMapping("/policies/finalization")
    public String createFinalizationPolicy(@RequestParam("name") final String name,
                                           @RequestParam("option") final String option,
                                           @RequestParam(value = "deleteAfterDays", required = false) final Long deleteAfterDays,
                                           final RedirectAttributes redirectAttributes) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/policies?tab=finalization";
        }
        if (!ai.philterd.arbiter.model.FinalizationPolicy.isValidOption(option)) {
            redirectAttributes.addFlashAttribute("error", "Pick a valid finalization option.");
            return "redirect:/policies?tab=finalization";
        }
        final long days = deleteAfterDays == null ? 0 : deleteAfterDays;
        if (ai.philterd.arbiter.model.FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS.equals(option)
                && days <= 0) {
            redirectAttributes.addFlashAttribute("error",
                    "\"Delete after X days\" requires a positive number of days.");
            return "redirect:/policies?tab=finalization";
        }
        if (finalizationPolicyRepository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A finalization policy named \"" + trimmed + "\" already exists.");
            return "redirect:/policies?tab=finalization";
        }
        final ai.philterd.arbiter.model.FinalizationPolicy policy =
                new ai.philterd.arbiter.model.FinalizationPolicy();
        policy.setId(UUID.randomUUID().toString());
        policy.setName(trimmed);
        policy.setOption(option);
        policy.setDeleteAfterDays(days);
        policy.setCreatedAt(java.time.Instant.now());
        policy.setUpdatedAt(policy.getCreatedAt());
        try {
            finalizationPolicyRepository.save(policy);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A finalization policy named \"" + trimmed + "\" already exists.");
            return "redirect:/policies?tab=finalization";
        }
        auditLogService.log("FINALIZATION_POLICY_CREATE", "FinalizationPolicy", policy.getId(),
                Map.of("name", trimmed, "option", option, "deleteAfterDays", days));
        redirectAttributes.addFlashAttribute("success",
                "Finalization policy \"" + trimmed + "\" created.");
        return "redirect:/policies?tab=finalization";
    }

    @PostMapping("/policies/finalization/{id}/edit")
    public String editFinalizationPolicy(@PathVariable final String id,
                                         @RequestParam("name") final String name,
                                         @RequestParam("option") final String option,
                                         @RequestParam(value = "deleteAfterDays", required = false) final Long deleteAfterDays,
                                         final RedirectAttributes redirectAttributes) {
        final ai.philterd.arbiter.model.FinalizationPolicy policy =
                finalizationPolicyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Finalization policy not found.");
            return "redirect:/policies?tab=finalization";
        }
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/policies?tab=finalization";
        }
        if (!ai.philterd.arbiter.model.FinalizationPolicy.isValidOption(option)) {
            redirectAttributes.addFlashAttribute("error", "Pick a valid finalization option.");
            return "redirect:/policies?tab=finalization";
        }
        final long days = deleteAfterDays == null ? 0 : deleteAfterDays;
        if (ai.philterd.arbiter.model.FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS.equals(option)
                && days <= 0) {
            redirectAttributes.addFlashAttribute("error",
                    "\"Delete after X days\" requires a positive number of days.");
            return "redirect:/policies?tab=finalization";
        }
        final java.util.Optional<ai.philterd.arbiter.model.FinalizationPolicy> existing =
                finalizationPolicyRepository.findByName(trimmed);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            redirectAttributes.addFlashAttribute("error",
                    "A finalization policy named \"" + trimmed + "\" already exists.");
            return "redirect:/policies?tab=finalization";
        }
        policy.setName(trimmed);
        policy.setOption(option);
        policy.setDeleteAfterDays(days);
        policy.setUpdatedAt(java.time.Instant.now());
        finalizationPolicyRepository.save(policy);
        auditLogService.log("FINALIZATION_POLICY_UPDATE", "FinalizationPolicy", policy.getId(),
                Map.of("name", trimmed, "option", option, "deleteAfterDays", days));
        redirectAttributes.addFlashAttribute("success",
                "Finalization policy \"" + trimmed + "\" updated.");
        return "redirect:/policies?tab=finalization";
    }

    @PostMapping("/policies/finalization/{id}/delete")
    public String deleteFinalizationPolicy(@PathVariable final String id,
                                           final RedirectAttributes redirectAttributes) {
        final ai.philterd.arbiter.model.FinalizationPolicy policy =
                finalizationPolicyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Finalization policy not found.");
            return "redirect:/policies?tab=finalization";
        }
        if (batchRepository.existsByFinalizationPolicyId(id)) {
            redirectAttributes.addFlashAttribute("error",
                    "Finalization policy \"" + policy.getName()
                            + "\" is in use by one or more batches and cannot be deleted.");
            return "redirect:/policies?tab=finalization";
        }
        finalizationPolicyRepository.deleteById(id);
        auditLogService.log("FINALIZATION_POLICY_DELETE", "FinalizationPolicy", id,
                Map.of("name", policy.getName() == null ? "" : policy.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Finalization policy \"" + policy.getName() + "\" deleted.");
        return "redirect:/policies?tab=finalization";
    }

    private String policyEditorUrl() {
        final String arbiterUrl = generalSettingsService.load().getArbiterUrl();
        if (arbiterUrl == null || arbiterUrl.isBlank()) {
            return "http://localhost:8081";
        }
        try {
            final URI uri = URI.create(arbiterUrl.trim());
            final String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            final String host = uri.getHost() == null ? "localhost" : uri.getHost();
            return scheme + "://" + host + ":8081";
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse Arbiter URL '{}': {}", arbiterUrl, e.getMessage());
            return "http://localhost:8081";
        }
    }

    @PostMapping("/policies")
    public String create(@RequestParam("name") final String name,
                         @RequestParam("content") final String content,
                         final RedirectAttributes redirectAttributes) {
        final String submittedName = name == null ? "" : name;
        final String submittedContent = content == null ? "" : content;
        final String trimmedName = submittedName.trim();
        final String trimmedContent = submittedContent.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Policy name is required.");
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }
        if (trimmedContent.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Policy JSON is required.");
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }
        try {
            objectMapper.readTree(trimmedContent);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Policy is not valid JSON: " + e.getMessage());
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }
        final Optional<Policy> existing = policyRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A policy named \"" + existing.get().getName() + "\" already exists.");
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }

        final Policy policy = new Policy();
        policy.setCreatedAt(LocalDateTime.now());
        policy.setId(UUID.randomUUID().toString());
        policy.setName(trimmedName);
        policy.setContent(trimmedContent);

        try {
            policyRepository.save(policy);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A policy named \"" + trimmedName + "\" already exists.");
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }
        auditLogService.log("POLICY_CREATE", "Policy", policy.getId(),
                Map.of("name", trimmedName, "size", trimmedContent.length()));
        redirectAttributes.addFlashAttribute("success",
                "Policy \"" + trimmedName + "\" added.");
        return "redirect:/policies";
    }

    @GetMapping("/api/v1/policies")
    @ResponseBody
    public Map<String, Object> listJson(@RequestParam(value = "instanceId", required = false) final String instanceId,
                                        final Authentication authentication) {
        // Defense-in-depth: SecurityConfig already gates /api/v1/policies to ADMIN+AUDITOR
        // via the framework matcher, but a future SecurityConfig change that drops the
        // matcher would otherwise expose policy listings to any authenticated caller.
        // Repeating the role check here makes the authorization local to the endpoint.
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(FORBIDDEN, "Not authorized.");
        }
        final String selected = (instanceId == null || instanceId.isBlank()) ? EMBEDDED : instanceId;
        List<String> names;
        if (EMBEDDED.equals(selected)) {
            names = new ArrayList<>();
            final org.springframework.data.domain.Page<Policy> allPoliciesPage =
                    policyRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
            final List<Policy> allPolicies = allPoliciesPage != null ? allPoliciesPage.getContent() : List.of();
            for (Policy p : allPolicies) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    names.add(p.getName());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
        } else {
            final PhilterInstance instance = philterInstanceRepository.findById(selected)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Philter instance not found."));
            try {
                names = fetchRemotePolicyNames(instance);
            } catch (Exception e) {
                log.warn("Failed to fetch policies from {}: {}", instance.getName(), e.getMessage());
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Could not reach Philter instance \"" + instance.getName() + "\": " + e.getMessage());
            }
        }
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("instanceId", selected);
        out.put("policies", names);
        return out;
    }

    @GetMapping("/api/v1/policies/content")
    @ResponseBody
    public Map<String, String> content(@RequestParam("instanceId") final String instanceId,
                                       @RequestParam("name") final String name,
                                       final Authentication authentication) {
        // Defense-in-depth role check — see listJson above for the rationale.
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(FORBIDDEN, "Not authorized.");
        }
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Policy name is required.");
        }
        if (!POLICY_NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Policy name must contain only letters, digits, hyphens, and underscores (1–64 characters).");
        }
        if (instanceId == null || instanceId.isBlank() || EMBEDDED.equals(instanceId)) {
            final Policy policy = policyRepository.findByName(name)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Policy not found: " + name));
            return Map.of("name", policy.getName(), "content", policy.getContent() == null ? "" : policy.getContent());
        }
        final PhilterInstance instance = philterInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Philter instance not found."));
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/policies/" + name))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                throw new ResponseStatusException(NOT_FOUND, "Policy \"" + name + "\" not found on instance \""
                        + instance.getName() + "\".");
            }
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Philter returned HTTP " + resp.statusCode() + " from /api/policies/" + name);
            }
            return Map.of("name", name, "content", resp.body() == null ? "" : resp.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to fetch policy {} from {}: {}", name, instance.getName(), e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not reach Philter instance \"" + instance.getName() + "\": " + e.getMessage());
        }
    }

    @PostMapping("/policies/{id}/edit")
    public String edit(@PathVariable final String id,
                       @RequestParam("content") final String content,
                       final RedirectAttributes redirectAttributes) {
        final Policy policy = policyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Policy not found.");
            return "redirect:/policies";
        }
        final String trimmedContent = content == null ? "" : content.trim();
        if (trimmedContent.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Policy JSON is required.");
            return "redirect:/policies";
        }
        try {
            objectMapper.readTree(trimmedContent);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Policy is not valid JSON: " + e.getMessage());
            return "redirect:/policies";
        }

        policy.setContent(trimmedContent);
        policyRepository.save(policy);
        auditLogService.log("POLICY_UPDATE", "Policy", policy.getId(),
                Map.of("name", policy.getName() == null ? "" : policy.getName(),
                        "size", trimmedContent.length()));
        redirectAttributes.addFlashAttribute("success",
                "Policy \"" + policy.getName() + "\" updated.");
        return "redirect:/policies";
    }

    @PostMapping("/policies/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final Policy policy = policyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Policy not found.");
            return "redirect:/policies";
        }
        final List<Batch> usingBatches = batchRepository.findByPhilterInstanceIdIsNullAndPolicyName(policy.getName());
        if (!usingBatches.isEmpty()) {
            final String names = usingBatches.stream()
                    .map(b -> "\"" + (b.getName() == null ? b.getId() : b.getName()) + "\"")
                    .collect(java.util.stream.Collectors.joining(", "));
            redirectAttributes.addFlashAttribute("error",
                    "Policy \"" + policy.getName() + "\" cannot be deleted because it is in use by batch "
                            + names + ".");
            return "redirect:/policies";
        }
        policyRepository.deleteById(id);
        auditLogService.log("POLICY_DELETE", "Policy", id,
                Map.of("name", policy.getName() == null ? "" : policy.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Policy \"" + policy.getName() + "\" removed.");
        return "redirect:/policies";
    }

    private List<String> fetchRemotePolicyNames(final PhilterInstance instance) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(instance) + "/api/policies"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from /api/policies");
        }
        final JsonNode root = objectMapper.readTree(resp.body());
        final List<String> names = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    names.add(node.asText());
                } else {
                    final JsonNode nameNode = node.get("name");
                    if (nameNode != null && !nameNode.asText().isBlank()) {
                        names.add(nameNode.asText());
                    }
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String baseUrl(final PhilterInstance instance) {
        String host = instance.getEndpoint();
        if (host == null) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }
}
