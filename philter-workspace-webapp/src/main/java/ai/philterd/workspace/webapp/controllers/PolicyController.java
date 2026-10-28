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
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.PolicySummary;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.PhilterError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class PolicyController {

    private static final Logger log = LoggerFactory.getLogger(PolicyController.class);
    private static final Pattern POLICY_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]{1,64}");

    private static final int POLICY_PAGE_SIZE = 100;

    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final GeneralSettingsService generalSettingsService;
    private final ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    private final PhilterSettings philter;

    public PolicyController(final BatchRepository batchRepository,
                            final AuditLogService auditLogService,
                            final GeneralSettingsService generalSettingsService,
                            final ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository,
                            final PhilterSettings philter) {
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
        this.generalSettingsService = generalSettingsService;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
        this.philter = philter;
    }

    @GetMapping("/policies")
    public String list(final Model model) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String name : fetchPolicyNames()) {
                final Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", name);
                rows.add(row);
            }
        } catch (Exception e) {
            final String detail = describe(e);
            log.warn("Failed to fetch policies from Philter: {}", detail);
            model.addAttribute("fetchError", "Could not reach Philter: " + detail);
        }
        model.addAttribute("policies", rows);
        model.addAttribute("policyEditorUrl", policyEditorUrl());

        // Finalization-policy tab data: list every policy plus per-policy "in use by N
        // batches" so the template can disable the Delete button when the policy is bound.
        final org.springframework.data.domain.Page<ai.philterd.workspace.model.FinalizationPolicy> finalizationPoliciesPage =
                finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<ai.philterd.workspace.model.FinalizationPolicy> finalizationPolicies =
                finalizationPoliciesPage != null ? finalizationPoliciesPage.getContent() : List.of();
        final List<Map<String, Object>> finalizationRows = new ArrayList<>();
        for (ai.philterd.workspace.model.FinalizationPolicy p : finalizationPolicies) {
            final Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("option", p.getOption());
            row.put("optionLabel", ai.philterd.workspace.model.FinalizationPolicy.labels()
                    .getOrDefault(p.getOption(), p.getOption()));
            row.put("deleteAfterDays", p.getDeleteAfterDays());
            row.put("inUse", batchRepository.existsByFinalizationPolicyId(p.getId()));
            finalizationRows.add(row);
        }
        model.addAttribute("finalizationPolicies", finalizationRows);
        model.addAttribute("finalizationOptions",
                ai.philterd.workspace.model.FinalizationPolicy.labels());
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
        if (!ai.philterd.workspace.model.FinalizationPolicy.isValidOption(option)) {
            redirectAttributes.addFlashAttribute("error", "Pick a valid finalization option.");
            return "redirect:/policies?tab=finalization";
        }
        final long days = deleteAfterDays == null ? 0 : deleteAfterDays;
        if (ai.philterd.workspace.model.FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS.equals(option)
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
        final ai.philterd.workspace.model.FinalizationPolicy policy =
                new ai.philterd.workspace.model.FinalizationPolicy();
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
        final ai.philterd.workspace.model.FinalizationPolicy policy =
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
        if (!ai.philterd.workspace.model.FinalizationPolicy.isValidOption(option)) {
            redirectAttributes.addFlashAttribute("error", "Pick a valid finalization option.");
            return "redirect:/policies?tab=finalization";
        }
        final long days = deleteAfterDays == null ? 0 : deleteAfterDays;
        if (ai.philterd.workspace.model.FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS.equals(option)
                && days <= 0) {
            redirectAttributes.addFlashAttribute("error",
                    "\"Delete after X days\" requires a positive number of days.");
            return "redirect:/policies?tab=finalization";
        }
        final java.util.Optional<ai.philterd.workspace.model.FinalizationPolicy> existing =
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
        final ai.philterd.workspace.model.FinalizationPolicy policy =
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
        final String workspaceUrl = generalSettingsService.load().getWorkspaceUrl();
        if (workspaceUrl == null || workspaceUrl.isBlank()) {
            return "http://localhost:8081";
        }
        try {
            final URI uri = URI.create(workspaceUrl.trim());
            final String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            final String host = uri.getHost() == null ? "localhost" : uri.getHost();
            return scheme + "://" + host + ":8081";
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse Philter Workspace URL '{}': {}", workspaceUrl, e.getMessage());
            return "http://localhost:8081";
        }
    }

    @GetMapping("/api/v1/policies")
    @ResponseBody
    public Map<String, Object> listJson(final Authentication authentication) {
        // Defense-in-depth: SecurityConfig already gates /api/v1/policies to ADMIN+AUDITOR
        // via the framework matcher, but a future SecurityConfig change that drops the
        // matcher would otherwise expose policy listings to any authenticated caller.
        // Repeating the role check here makes the authorization local to the endpoint.
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(FORBIDDEN, "Not authorized.");
        }
        final List<String> names;
        try {
            names = fetchPolicyNames();
        } catch (Exception e) {
            final String detail = describe(e);
            log.warn("Failed to fetch policies from Philter: {}", detail);
            throw new ResponseStatusException(BAD_GATEWAY, "Could not reach Philter: " + detail);
        }
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("policies", names);
        return out;
    }

    @GetMapping("/api/v1/policies/content")
    @ResponseBody
    public Map<String, String> content(@RequestParam("name") final String name,
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
        try {
            final String content = philter.client().getPolicy(name);
            return Map.of("name", name, "content", content == null ? "" : content);
        } catch (ClientException e) {
            if (e.getStatusCode() == 404) {
                throw new ResponseStatusException(NOT_FOUND, "Policy \"" + name + "\" not found in Philter.");
            }
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Philter returned " + describe(e) + " for policy " + name);
        } catch (Exception e) {
            final String detail = describe(e);
            log.warn("Failed to fetch policy {} from Philter: {}", name, detail);
            throw new ResponseStatusException(BAD_GATEWAY, "Could not reach Philter: " + detail);
        }
    }

    private List<String> fetchPolicyNames() throws Exception {
        final ai.philterd.philter.PhilterClient client = philter.client();
        final List<String> names = new ArrayList<>();
        Page<PolicySummary> page;
        do {
            page = client.listPolicies(null, new ListOptions().offset(names.size()).limit(POLICY_PAGE_SIZE));
            for (PolicySummary policy : page.getItems()) {
                names.add(policy.getName());
            }
        } while (!page.getItems().isEmpty() && names.size() < page.getTotal());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** A failed Philter call's status, or the exception's own message when Philter wasn't reached. */
    private static String describe(final Exception e) {
        if (e instanceof PhilterError) {
            return PhilterClients.describe(e);
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
