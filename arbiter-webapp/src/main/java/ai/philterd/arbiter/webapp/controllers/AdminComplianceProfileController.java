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
import ai.philterd.arbiter.model.ComplianceProfile;
import ai.philterd.arbiter.model.ExemptionCode;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.ComplianceProfileRepository;
import ai.philterd.arbiter.service.AuditLogService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/compliance-profiles")
public class AdminComplianceProfileController {

    private final ComplianceProfileRepository complianceProfileRepository;
    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;

    public AdminComplianceProfileController(final ComplianceProfileRepository complianceProfileRepository,
                                            final BatchRepository batchRepository,
                                            final AuditLogService auditLogService) {
        this.complianceProfileRepository = complianceProfileRepository;
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String list(@RequestParam(value = "showArchived", defaultValue = "false") final boolean showArchived,
                       final Model model) {
        List<ComplianceProfile> profiles = complianceProfileRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        if (!showArchived) {
            profiles = profiles.stream().filter(p -> !p.isArchived()).collect(java.util.stream.Collectors.toList());
        }
        model.addAttribute("profiles", profiles);
        model.addAttribute("showArchived", showArchived);

        final Map<String, List<ExemptionCode>> profileExemptionCodesMap = new java.util.LinkedHashMap<>();
        for (final ComplianceProfile p : profiles) {
            if (!p.isPreset() && !p.isArchived()) {
                profileExemptionCodesMap.put(p.getId(), p.getExemptionCodes() != null ? p.getExemptionCodes() : List.of());
            }
        }
        model.addAttribute("profileExemptionCodesMap", profileExemptionCodesMap);

        return "admin-compliance-profiles";
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam(value = "codes", required = false) final List<String> codesParam,
                         @RequestParam(value = "descriptions", required = false) final List<String> descriptionsParam,
                         final RedirectAttributes redirectAttributes) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Profile name is required.");
            redirectAttributes.addFlashAttribute("retainName", name);
            return "redirect:/admin/compliance-profiles";
        }
        if (complianceProfileRepository.findByName(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "A compliance profile named \"" + trimmed + "\" already exists.");
            redirectAttributes.addFlashAttribute("retainName", name);
            return "redirect:/admin/compliance-profiles";
        }

        final List<ExemptionCode> exemptionCodes = buildExemptionCodes(codesParam, descriptionsParam);
        final List<String> dupes = duplicateCodesIn(exemptionCodes);
        if (!dupes.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Duplicate exemption codes are not allowed: " + String.join(", ", dupes) + ".");
            redirectAttributes.addFlashAttribute("retainName", name);
            return "redirect:/admin/compliance-profiles";
        }

        final ComplianceProfile profile = new ComplianceProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(trimmed);
        profile.setExemptionCodes(exemptionCodes);
        profile.setCreatedAt(LocalDateTime.now());
        complianceProfileRepository.save(profile);
        auditLogService.log("COMPLIANCE_PROFILE_CREATE", "ComplianceProfile", profile.getId(),
                Map.of("name", trimmed, "exemptionCodeCount", exemptionCodes.size()));
        redirectAttributes.addFlashAttribute("success", "Compliance profile \"" + trimmed + "\" created.");
        return "redirect:/admin/compliance-profiles";
    }

    @PostMapping("/{profileId}/edit")
    public String edit(@PathVariable final String profileId,
                       @RequestParam(value = "codes", required = false) final List<String> codesParam,
                       @RequestParam(value = "descriptions", required = false) final List<String> descriptionsParam,
                       final RedirectAttributes redirectAttributes) {
        final ComplianceProfile profile = complianceProfileRepository.findById(profileId).orElse(null);
        if (profile == null) {
            redirectAttributes.addFlashAttribute("error", "Compliance profile not found.");
            return "redirect:/admin/compliance-profiles";
        }
        if (profile.isPreset()) {
            redirectAttributes.addFlashAttribute("error", "Preset compliance profiles cannot be edited.");
            return "redirect:/admin/compliance-profiles";
        }
        final List<ExemptionCode> newCodes = buildExemptionCodes(codesParam, descriptionsParam);
        final List<String> dupesInNew = duplicateCodesIn(newCodes);
        if (!dupesInNew.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Duplicate exemption codes are not allowed: " + String.join(", ", dupesInNew) + ".");
            return "redirect:/admin/compliance-profiles";
        }
        final List<ExemptionCode> existing = profile.getExemptionCodes() != null ? profile.getExemptionCodes() : List.of();
        final List<String> existingCodeValues = existing.stream().map(ExemptionCode::getCode).toList();
        final List<String> conflicts = newCodes.stream().map(ExemptionCode::getCode).filter(existingCodeValues::contains).toList();
        if (!conflicts.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "The following exemption codes already exist in this profile: " + String.join(", ", conflicts) + ".");
            return "redirect:/admin/compliance-profiles";
        }
        final List<ExemptionCode> merged = new ArrayList<>(existing);
        merged.addAll(newCodes);
        profile.setExemptionCodes(merged);
        complianceProfileRepository.save(profile);
        auditLogService.log("COMPLIANCE_PROFILE_EDIT", "ComplianceProfile", profileId,
                Map.of("name", profile.getName() == null ? "" : profile.getName(), "exemptionCodeCount", merged.size()));
        redirectAttributes.addFlashAttribute("success", "Compliance profile \"" + profile.getName() + "\" updated.");
        return "redirect:/admin/compliance-profiles";
    }

    @PostMapping("/{profileId}/archive")
    public String archive(@PathVariable final String profileId, final RedirectAttributes redirectAttributes) {
        final ComplianceProfile profile = complianceProfileRepository.findById(profileId).orElse(null);
        if (profile == null) {
            redirectAttributes.addFlashAttribute("error", "Compliance profile not found.");
            return "redirect:/admin/compliance-profiles";
        }
        if (profile.isPreset()) {
            redirectAttributes.addFlashAttribute("error", "Preset compliance profiles cannot be archived.");
            return "redirect:/admin/compliance-profiles";
        }
        if (profile.isArchived()) {
            redirectAttributes.addFlashAttribute("error", "Compliance profile is already archived.");
            return "redirect:/admin/compliance-profiles";
        }
        final List<Batch> openBatches = batchRepository.findByComplianceProfileId(profileId)
                .stream().filter(b -> !b.isClosed()).toList();
        if (!openBatches.isEmpty()) {
            final String names = openBatches.stream().map(Batch::getName).collect(java.util.stream.Collectors.joining(", "));
            redirectAttributes.addFlashAttribute("error",
                    "Cannot archive \"" + profile.getName() + "\": the following batches are still open: " + names + ".");
            return "redirect:/admin/compliance-profiles";
        }
        profile.setArchived(true);
        profile.setArchivedAt(LocalDateTime.now());
        complianceProfileRepository.save(profile);
        auditLogService.log("COMPLIANCE_PROFILE_ARCHIVE", "ComplianceProfile", profileId,
                Map.of("name", profile.getName() == null ? "" : profile.getName()));
        redirectAttributes.addFlashAttribute("success", "Compliance profile \"" + profile.getName() + "\" archived.");
        return "redirect:/admin/compliance-profiles";
    }

    @PostMapping("/{profileId}/delete")
    public String delete(@PathVariable final String profileId, final RedirectAttributes redirectAttributes) {
        final ComplianceProfile profile = complianceProfileRepository.findById(profileId).orElse(null);
        if (profile == null) {
            redirectAttributes.addFlashAttribute("error", "Compliance profile not found.");
            return "redirect:/admin/compliance-profiles";
        }
        if (profile.isPreset()) {
            redirectAttributes.addFlashAttribute("error", "Preset compliance profiles cannot be deleted.");
            return "redirect:/admin/compliance-profiles";
        }
        complianceProfileRepository.deleteById(profileId);
        auditLogService.log("COMPLIANCE_PROFILE_DELETE", "ComplianceProfile", profileId,
                Map.of("name", profile.getName() == null ? "" : profile.getName()));
        redirectAttributes.addFlashAttribute("success", "Compliance profile \"" + profile.getName() + "\" deleted.");
        return "redirect:/admin/compliance-profiles";
    }

    private List<ExemptionCode> buildExemptionCodes(final List<String> codes, final List<String> descriptions) {
        final List<ExemptionCode> result = new ArrayList<>();
        if (codes == null) return result;
        for (int i = 0; i < codes.size(); i++) {
            final String code = codes.get(i) == null ? "" : codes.get(i).trim();
            if (code.isEmpty()) continue;
            final String desc = (descriptions != null && i < descriptions.size() && descriptions.get(i) != null)
                    ? descriptions.get(i).trim() : "";
            result.add(new ExemptionCode(code, desc));
        }
        return result;
    }

    private List<String> duplicateCodesIn(final List<ExemptionCode> exemptionCodes) {
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        final List<String> dupes = new ArrayList<>();
        for (final ExemptionCode ec : exemptionCodes) {
            final String code = ec.getCode();
            if (!seen.add(code) && !dupes.contains(code)) {
                dupes.add(code);
            }
        }
        return dupes;
    }
}
