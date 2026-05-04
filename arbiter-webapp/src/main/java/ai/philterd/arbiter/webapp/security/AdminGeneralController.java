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
import java.util.Map;

@Controller
@RequestMapping("/admin/general")
public class AdminGeneralController {

    private final GeneralSettingsService generalSettingsService;
    private final AuditLogService auditLogService;

    public AdminGeneralController(GeneralSettingsService generalSettingsService,
                                  AuditLogService auditLogService) {
        this.generalSettingsService = generalSettingsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String form(Model model) {
        model.addAttribute("settings", generalSettingsService.load());
        return "admin-general";
    }

    @PostMapping
    public String save(@RequestParam("arbiterUrl") String arbiterUrl,
                       RedirectAttributes redirectAttributes) {
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

        GeneralSettings settings = generalSettingsService.load();
        String previous = settings.getArbiterUrl();
        settings.setArbiterUrl(trimmed);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousArbiterUrl", previous == null ? "" : previous,
                        "arbiterUrl", trimmed));
        redirectAttributes.addFlashAttribute("success", "General settings saved.");
        return "redirect:/admin/general";
    }
}
