/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.Map;

@Controller
@RequestMapping("/admin/security")
public class AdminSecurityController {

    private final GeneralSettingsService generalSettingsService;
    private final AuditLogService auditLogService;

    public AdminSecurityController(final GeneralSettingsService generalSettingsService,
                                   final AuditLogService auditLogService) {
        this.generalSettingsService = generalSettingsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String form(final Model model) {
        model.addAttribute("settings", generalSettingsService.load());
        return "admin-security";
    }

    @PostMapping("/mfa")
    public String saveMfaPolicy(
            @RequestParam(value = "requireMfa", defaultValue = "false") final boolean requireMfa,
            final RedirectAttributes redirectAttributes) {
        final GeneralSettings settings = generalSettingsService.load();
        final boolean previous = settings.isRequireMfa();
        settings.setRequireMfa(requireMfa);
        generalSettingsService.save(settings);
        auditLogService.log("SECURITY_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousRequireMfa", previous, "requireMfa", requireMfa));
        redirectAttributes.addFlashAttribute("success", requireMfa
                ? "MFA is now required for all users."
                : "MFA requirement removed.");
        return "redirect:/admin/security";
    }

    /**
     * Toggle the data-source host allow-list. Disabling it removes the SSRF defence
     * that gates outbound connections to OpenSearch / Elasticsearch / Philter / Ollama
     * endpoints by hostname, so the audit row records exactly who flipped it and when.
     */
    @PostMapping("/host-allow-list")
    public String saveHostAllowList(
            @RequestParam(value = "hostAllowListEnabled", defaultValue = "false")
            final boolean hostAllowListEnabled,
            final RedirectAttributes redirectAttributes) {
        final GeneralSettings settings = generalSettingsService.load();
        final boolean previous = settings.isHostAllowListEnabled();
        settings.setHostAllowListEnabled(hostAllowListEnabled);
        generalSettingsService.save(settings);
        auditLogService.log("SECURITY_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of("previousHostAllowListEnabled", previous,
                        "hostAllowListEnabled", hostAllowListEnabled));
        redirectAttributes.addFlashAttribute("success", hostAllowListEnabled
                ? "Host allow-list is now enabled."
                : "Host allow-list is now disabled. Outbound endpoints will not be checked against the allow-list.");
        return "redirect:/admin/security";
    }
}
