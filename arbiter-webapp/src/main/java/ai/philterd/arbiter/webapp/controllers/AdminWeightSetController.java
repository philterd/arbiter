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
import ai.philterd.arbiter.model.PiiTypes;
import ai.philterd.arbiter.model.PiiWeights;
import ai.philterd.arbiter.model.WeightSet;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.WeightSetRepository;
import ai.philterd.arbiter.service.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/weights")
public class AdminWeightSetController {

    private final WeightSetRepository repository;
    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;

    public AdminWeightSetController(final WeightSetRepository repository,
                                    final BatchRepository batchRepository,
                                    final AuditLogService auditLogService) {
        this.repository = repository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String list(final Model model) {
        final List<WeightSet> sets = repository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (WeightSet ws : sets) {
            final int usage = batchRepository.findByWeightSetId(ws.getId()).size();
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ws.getId());
            row.put("name", ws.getName());
            row.put("usageCount", usage);
            rows.add(row);
        }
        model.addAttribute("weightSets", rows);
        return "admin-weights";
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         final RedirectAttributes redirectAttributes) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/weights";
        }
        if (repository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A weight set named \"" + trimmed + "\" already exists.");
            return "redirect:/admin/weights";
        }

        final WeightSet set = new WeightSet();
        set.setCreatedAt(LocalDateTime.now());
        set.setId(UUID.randomUUID().toString());
        set.setName(trimmed);
        // Seed with the current default weight for every PII type so the
        // editor opens with sensible values.
        set.setWeights(new LinkedHashMap<>(PiiWeights.effective(null)));

        try {
            repository.save(set);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A weight set named \"" + trimmed + "\" already exists.");
            return "redirect:/admin/weights";
        }

        auditLogService.log("WEIGHT_SET_CREATE", "WeightSet", set.getId(),
                Map.of("name", trimmed));
        redirectAttributes.addFlashAttribute("success",
                "Weight set \"" + trimmed + "\" created.");
        return "redirect:/admin/weights/" + set.getId();
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable final String id, final Model model,
                       final RedirectAttributes redirectAttributes) {
        final WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }

        final Map<String, Integer> values = set.getWeights() == null ? Map.of() : set.getWeights();
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (String type : PiiTypes.values()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("label", PiiTypes.labelFor(type));
            row.put("weight", values.getOrDefault(type, PiiWeights.weightFor(type, null)));
            row.put("defaultWeight", PiiWeights.weightFor(type, null));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> ((String) r.get("label")).toLowerCase()));

        model.addAttribute("weightSet", set);
        model.addAttribute("weightRows", rows);
        return "admin-weights-edit";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable final String id,
                       @RequestParam("name") final String name,
                       @RequestParam(value = "type", required = false) final List<String> types,
                       @RequestParam(value = "weight", required = false) final List<Integer> weights,
                       final RedirectAttributes redirectAttributes) {
        final WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }

        final String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/weights/" + id;
        }
        final boolean nameTaken = repository.findByName(trimmedName)
                .filter(other -> !other.getId().equals(id))
                .isPresent();
        if (nameTaken) {
            redirectAttributes.addFlashAttribute("error",
                    "A weight set named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/weights/" + id;
        }
        if (types == null || weights == null || types.size() != weights.size()) {
            redirectAttributes.addFlashAttribute("error", "Invalid weights submission.");
            return "redirect:/admin/weights/" + id;
        }

        final Map<String, Integer> updated = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            final String type = types.get(i);
            final Integer w = weights.get(i);
            if (type == null || w == null) continue;
            final String key = type.trim().toLowerCase();
            if (!PiiTypes.isValid(key)) continue;
            if (w < 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Weight for " + PiiTypes.labelFor(key) + " must be 0 or greater.");
                return "redirect:/admin/weights/" + id;
            }
            updated.put(key, w);
        }

        set.setName(trimmedName);
        set.setWeights(updated);
        try {
            repository.save(set);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A weight set named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/weights/" + id;
        }

        auditLogService.log("WEIGHT_SET_UPDATE", "WeightSet", id,
                Map.of("name", trimmedName, "weightCount", updated.size()));
        redirectAttributes.addFlashAttribute("success",
                "Weight set \"" + trimmedName + "\" saved.");
        // After a successful save, return to the listing — the user is done editing
        // and the list view shows the persisted change in context with everything else.
        return "redirect:/admin/weights";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }
        final List<Batch> usingBatches = batchRepository.findByWeightSetId(id);
        if (!usingBatches.isEmpty()) {
            final String names = usingBatches.stream()
                    .map(b -> "\"" + (b.getName() == null ? "" : b.getName()) + "\"")
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            final String suffix = usingBatches.size() > 5
                    ? " and " + (usingBatches.size() - 5) + " more" : "";
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Cannot remove \"" + set.getName() + "\": still in use by "
                            + usingBatches.size() + " batch"
                            + (usingBatches.size() == 1 ? "" : "es")
                            + " (" + names + suffix + "). "
                            + "Load a different preset on those batches first.");
            return "redirect:/admin/weights";
        }
        repository.deleteById(id);
        auditLogService.log("WEIGHT_SET_DELETE", "WeightSet", id,
                Map.of("name", set.getName() == null ? "" : set.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Weight set \"" + set.getName() + "\" removed.");
        return "redirect:/admin/weights";
    }
}
