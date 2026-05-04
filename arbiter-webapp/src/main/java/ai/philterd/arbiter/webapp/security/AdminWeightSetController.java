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
package ai.philterd.arbiter.webapp.security;

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

import java.util.ArrayList;
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

    public AdminWeightSetController(WeightSetRepository repository,
                                    BatchRepository batchRepository,
                                    AuditLogService auditLogService) {
        this.repository = repository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String list(Model model) {
        List<WeightSet> sets = repository.findAll();
        sets.sort(Comparator.comparing(
                (WeightSet w) -> w.getName() == null ? "" : w.getName().toLowerCase()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeightSet ws : sets) {
            int usage = batchRepository.findByWeightSetId(ws.getId()).size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ws.getId());
            row.put("name", ws.getName());
            row.put("usageCount", usage);
            rows.add(row);
        }
        model.addAttribute("weightSets", rows);
        return "admin-weights";
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         RedirectAttributes redirectAttributes) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/weights";
        }
        if (repository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A weight set named \"" + trimmed + "\" already exists.");
            return "redirect:/admin/weights";
        }

        WeightSet set = new WeightSet();
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
    public String edit(@PathVariable String id, Model model,
                       RedirectAttributes redirectAttributes) {
        WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }

        Map<String, Integer> values = set.getWeights() == null ? Map.of() : set.getWeights();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String type : PiiTypes.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
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
    public String save(@PathVariable String id,
                       @RequestParam("name") String name,
                       @RequestParam(value = "type", required = false) List<String> types,
                       @RequestParam(value = "weight", required = false) List<Integer> weights,
                       RedirectAttributes redirectAttributes) {
        WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }

        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/weights/" + id;
        }
        boolean nameTaken = repository.findByName(trimmedName)
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

        Map<String, Integer> updated = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            Integer w = weights.get(i);
            if (type == null || w == null) continue;
            String key = type.trim().toLowerCase();
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
        return "redirect:/admin/weights/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
        WeightSet set = repository.findById(id).orElse(null);
        if (set == null) {
            redirectAttributes.addFlashAttribute("error", "Weight set not found.");
            return "redirect:/admin/weights";
        }
        List<Batch> usingBatches = batchRepository.findByWeightSetId(id);
        if (!usingBatches.isEmpty()) {
            String names = usingBatches.stream()
                    .map(b -> "\"" + (b.getName() == null ? "" : b.getName()) + "\"")
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            String suffix = usingBatches.size() > 5
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
