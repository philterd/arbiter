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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/general")
public class AdminGeneralController {

    private final GeneralSettingsService generalSettingsService;
    private final AuditLogService auditLogService;

    public AdminGeneralController(final GeneralSettingsService generalSettingsService,
                                  final AuditLogService auditLogService) {
        this.generalSettingsService = generalSettingsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String form(final Model model) {
        model.addAttribute("settings", generalSettingsService.load());
        model.addAttribute("timezones", availableTimezones());
        return "admin-general";
    }

    @PostMapping("/url")
    public String saveUrl(@RequestParam("arbiterUrl") final String arbiterUrl,
                          final RedirectAttributes redirectAttributes) {
        String trimmed = arbiterUrl == null ? "" : arbiterUrl.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Arbiter URL is required.");
            return "redirect:/admin/general";
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            redirectAttributes.addFlashAttribute("error", "Arbiter URL must start with http:// or https://.");
            return "redirect:/admin/general";
        }
        try {
            URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Arbiter URL is not a valid URI: " + e.getMessage());
            return "redirect:/admin/general";
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        final GeneralSettings settings = generalSettingsService.load();
        final String previous = settings.getArbiterUrl();
        settings.setArbiterUrl(trimmed);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousArbiterUrl", previous == null ? "" : previous,
                        "arbiterUrl", trimmed));
        redirectAttributes.addFlashAttribute("success", "Arbiter URL saved.");
        return "redirect:/admin/general";
    }

    @PostMapping("/opensearch-endpoint")
    public String saveOpensearchEndpoint(@RequestParam("opensearchEndpoint") final String opensearchEndpoint,
                                         final RedirectAttributes redirectAttributes) {
        String trimmed = opensearchEndpoint == null ? "" : opensearchEndpoint.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "OpenSearch endpoint is required.");
            return "redirect:/admin/general";
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            redirectAttributes.addFlashAttribute("error", "OpenSearch endpoint must start with http:// or https://.");
            return "redirect:/admin/general";
        }
        try {
            URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "OpenSearch endpoint is not a valid URI: " + e.getMessage());
            return "redirect:/admin/general";
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        final GeneralSettings settings = generalSettingsService.load();
        final String previous = settings.getOpensearchEndpoint();
        settings.setOpensearchEndpoint(trimmed);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousOpensearchEndpoint", previous == null ? "" : previous,
                        "opensearchEndpoint", trimmed));
        redirectAttributes.addFlashAttribute("success", "OpenSearch endpoint saved.");
        return "redirect:/admin/general";
    }

    @PostMapping("/max-upload-mb")
    public String saveMaxUploadFileSize(@RequestParam("maxUploadMb") final double maxUploadMb,
                                        final RedirectAttributes redirectAttributes) {
        if (Double.isNaN(maxUploadMb) || maxUploadMb <= 0) {
            redirectAttributes.addFlashAttribute("error",
                    "Max upload size must be greater than 0 MB.");
            return "redirect:/admin/general";
        }
        final long bytes = (long) Math.round(maxUploadMb * 1024.0 * 1024.0);

        final GeneralSettings settings = generalSettingsService.load();
        final long previous = settings.getMaxUploadFileSizeBytes();
        settings.setMaxUploadFileSizeBytes(bytes);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousMaxUploadFileSizeBytes", previous,
                        "maxUploadFileSizeBytes", bytes));
        redirectAttributes.addFlashAttribute("success", "Max upload file size saved.");
        return "redirect:/admin/general";
    }

    @PostMapping("/max-concurrent-data-imports")
    public String saveMaxConcurrentDataImports(
            @RequestParam("maxConcurrentDataImports") final int maxConcurrentDataImports,
            final RedirectAttributes redirectAttributes) {
        if (maxConcurrentDataImports < GeneralSettingsService.MIN_CONCURRENT_DATA_IMPORTS
                || maxConcurrentDataImports > GeneralSettingsService.MAX_CONCURRENT_DATA_IMPORTS) {
            redirectAttributes.addFlashAttribute("error",
                    "Max concurrent data imports must be between "
                            + GeneralSettingsService.MIN_CONCURRENT_DATA_IMPORTS + " and "
                            + GeneralSettingsService.MAX_CONCURRENT_DATA_IMPORTS + ".");
            return "redirect:/admin/general";
        }

        final GeneralSettings settings = generalSettingsService.load();
        final int previous = settings.getMaxConcurrentDataImports();
        settings.setMaxConcurrentDataImports(maxConcurrentDataImports);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousMaxConcurrentDataImports", previous,
                        "maxConcurrentDataImports", maxConcurrentDataImports));
        redirectAttributes.addFlashAttribute("success", "Max concurrent data imports saved.");
        return "redirect:/admin/general";
    }

    @PostMapping("/timezone")
    public String saveTimezone(@RequestParam("timezone") final String timezone,
                               final RedirectAttributes redirectAttributes) {
        final String tz = timezone == null ? "" : timezone.trim();
        if (tz.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Timezone is required.");
            return "redirect:/admin/general";
        }
        try {
            ZoneId.of(tz);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Timezone \"" + tz + "\" is not a valid IANA zone.");
            return "redirect:/admin/general";
        }

        final GeneralSettings settings = generalSettingsService.load();
        final String previous = settings.getTimezone();
        settings.setTimezone(tz);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousTimezone", previous == null ? "" : previous,
                        "timezone", tz));
        redirectAttributes.addFlashAttribute("success", "Timezone saved.");
        return "redirect:/admin/general";
    }

    private static List<String> availableTimezones() {
        final List<String> zones = new ArrayList<>();
        zones.add("UTC");
        ZoneId.getAvailableZoneIds().stream()
                .filter(id -> id.contains("/"))
                .filter(id -> !id.startsWith("Etc/") && !id.startsWith("SystemV/"))
                .sorted()
                .forEach(zones::add);
        return zones;
    }
}
