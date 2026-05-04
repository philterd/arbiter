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
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.model.Policy;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.PolicyRepository;
import ai.philterd.arbiter.service.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.net.URI;
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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final PolicyRepository policyRepository;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public PolicyController(PolicyRepository policyRepository,
                            PhilterInstanceRepository philterInstanceRepository,
                            BatchRepository batchRepository,
                            AuditLogService auditLogService,
                            ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.philterInstanceRepository = philterInstanceRepository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/policies")
    public String list(@RequestParam(value = "instanceId", required = false) String instanceId,
                       Model model) {
        List<PhilterInstance> instances = philterInstanceRepository.findAll();
        instances.sort(Comparator.comparing(
                (PhilterInstance i) -> i.getName() == null ? "" : i.getName().toLowerCase()));

        String selected = (instanceId == null || instanceId.isBlank()) ? EMBEDDED : instanceId;
        boolean isEmbedded = EMBEDDED.equals(selected);

        List<Map<String, Object>> rows = new ArrayList<>();
        String fetchError = null;
        String selectedInstanceName = "Embedded Philter";

        if (isEmbedded) {
            List<Policy> policies = policyRepository.findAll();
            policies.sort(Comparator.comparing(
                    (Policy p) -> p.getName() == null ? "" : p.getName().toLowerCase()));
            for (Policy p : policies) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", p.getId());
                row.put("name", p.getName());
                rows.add(row);
            }
        } else {
            Optional<PhilterInstance> instance = philterInstanceRepository.findById(selected);
            if (instance.isEmpty()) {
                fetchError = "Selected Philter instance no longer exists.";
            } else {
                selectedInstanceName = instance.get().getName();
                try {
                    for (String name : fetchRemotePolicyNames(instance.get())) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
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
        if (fetchError != null) {
            model.addAttribute("fetchError", fetchError);
        }
        return "policies";
    }

    @PostMapping("/policies")
    public String create(@RequestParam("name") String name,
                         @RequestParam("content") String content,
                         RedirectAttributes redirectAttributes) {
        String submittedName = name == null ? "" : name;
        String submittedContent = content == null ? "" : content;
        String trimmedName = submittedName.trim();
        String trimmedContent = submittedContent.trim();

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
        Optional<Policy> existing = policyRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A policy named \"" + existing.get().getName() + "\" already exists.");
            redirectAttributes.addFlashAttribute("formName", submittedName);
            redirectAttributes.addFlashAttribute("formContent", submittedContent);
            return "redirect:/policies";
        }

        Policy policy = new Policy();
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
    public Map<String, Object> listJson(@RequestParam(value = "instanceId", required = false) String instanceId) {
        String selected = (instanceId == null || instanceId.isBlank()) ? EMBEDDED : instanceId;
        List<String> names;
        if (EMBEDDED.equals(selected)) {
            names = new ArrayList<>();
            for (Policy p : policyRepository.findAll()) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    names.add(p.getName());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
        } else {
            PhilterInstance instance = philterInstanceRepository.findById(selected)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Philter instance not found."));
            try {
                names = fetchRemotePolicyNames(instance);
            } catch (Exception e) {
                log.warn("Failed to fetch policies from {}: {}", instance.getName(), e.getMessage());
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Could not reach Philter instance \"" + instance.getName() + "\": " + e.getMessage());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("instanceId", selected);
        out.put("policies", names);
        return out;
    }

    @GetMapping("/api/v1/policies/content")
    @ResponseBody
    public Map<String, String> content(@RequestParam("instanceId") String instanceId,
                                       @RequestParam("name") String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Policy name is required.");
        }
        if (instanceId == null || instanceId.isBlank() || EMBEDDED.equals(instanceId)) {
            Policy policy = policyRepository.findByName(name)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Policy not found: " + name));
            return Map.of("name", policy.getName(), "content", policy.getContent() == null ? "" : policy.getContent());
        }
        PhilterInstance instance = philterInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Philter instance not found."));
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/policies/" + name))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
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
    public String edit(@PathVariable String id,
                       @RequestParam("content") String content,
                       RedirectAttributes redirectAttributes) {
        Policy policy = policyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Policy not found.");
            return "redirect:/policies";
        }
        String trimmedContent = content == null ? "" : content.trim();
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
    public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
        Policy policy = policyRepository.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("error", "Policy not found.");
            return "redirect:/policies";
        }
        List<Batch> usingBatches = batchRepository.findByPhilterInstanceIdIsNullAndPolicyName(policy.getName());
        if (!usingBatches.isEmpty()) {
            String names = usingBatches.stream()
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

    private List<String> fetchRemotePolicyNames(PhilterInstance instance) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(instance) + "/api/policies"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from /api/policies");
        }
        JsonNode root = objectMapper.readTree(resp.body());
        List<String> names = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    names.add(node.asText());
                } else {
                    JsonNode nameNode = node.get("name");
                    if (nameNode != null && !nameNode.asText().isBlank()) {
                        names.add(nameNode.asText());
                    }
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String baseUrl(PhilterInstance instance) {
        String host = instance.getEndpoint();
        if (host == null) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }
}
