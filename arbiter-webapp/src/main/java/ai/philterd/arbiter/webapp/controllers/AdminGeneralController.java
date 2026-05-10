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

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.FullTextSearchIndexManager;
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
    private final FullTextSearchIndexManager indexManager;

    public AdminGeneralController(final GeneralSettingsService generalSettingsService,
                                  final AuditLogService auditLogService,
                                  final FullTextSearchIndexManager indexManager) {
        this.generalSettingsService = generalSettingsService;
        this.auditLogService = auditLogService;
        this.indexManager = indexManager;
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

    /**
     * Save the Full Text Search settings — the master enabled flag, OpenSearch endpoint,
     * optional basic-auth username + password, and index name. After validation the
     * controller probes the cluster (via {@link FullTextSearchIndexManager#ensureIndex}):
     *
     * <ul>
     *   <li>If the index does not exist, it is created with the canonical mapping and the
     *       settings are saved.</li>
     *   <li>If the index exists with the canonical mapping, the settings are saved.</li>
     *   <li>If the index exists but its mapping <strong>differs</strong>, the settings are
     *       NOT saved yet — the form is re-rendered with a side-by-side mapping diff and a
     *       "Continue with existing index" / "Cancel" prompt. {@link #confirmFullTextSearch}
     *       is the follow-up endpoint when the operator chooses to continue.</li>
     *   <li>If OpenSearch is unreachable or the feature is being turned off, the settings
     *       are saved without an index probe — turning the feature off should never depend
     *       on the cluster being live.</li>
     * </ul>
     *
     * Password handling follows the data-source pattern: blank input keeps whatever was
     * stored before; a non-blank value replaces it. {@code clearOpensearchPassword=true}
     * wipes the stored value back to null.
     */
    @PostMapping("/full-text-search")
    public String saveFullTextSearch(
            @RequestParam(value = "fullTextSearchEnabled", required = false) final Boolean fullTextSearchEnabled,
            @RequestParam("opensearchEndpoint") final String opensearchEndpoint,
            @RequestParam("opensearchIndexName") final String opensearchIndexName,
            @RequestParam(value = "opensearchUsername", required = false) final String opensearchUsername,
            @RequestParam(value = "opensearchPassword", required = false) final String opensearchPassword,
            @RequestParam(value = "clearOpensearchPassword", required = false) final Boolean clearOpensearchPassword,
            final RedirectAttributes redirectAttributes) {

        final boolean enabled = fullTextSearchEnabled != null && fullTextSearchEnabled;
        final FtsFormResult parsed = parseFtsForm(opensearchEndpoint, opensearchIndexName,
                opensearchUsername, opensearchPassword, clearOpensearchPassword);
        if (parsed.error() != null) {
            redirectAttributes.addFlashAttribute("error", parsed.error());
            return "redirect:/admin/general";
        }

        final GeneralSettings settings = generalSettingsService.load();
        final String resolvedPassword = resolvePassword(settings, parsed);

        // When the feature is being turned off, skip the OpenSearch probe entirely. An
        // operator turning indexing off should never have to wait for a (possibly down)
        // cluster — that's why they're turning it off.
        if (!enabled) {
            return persist(settings, enabled, parsed, resolvedPassword, redirectAttributes,
                    "Full text search disabled.");
        }

        final FullTextSearchIndexManager.Result probe = indexManager.ensureIndex(
                parsed.endpoint(), parsed.indexName(), parsed.username(), resolvedPassword);
        switch (probe.outcome()) {
            case CREATED, ALREADY_MATCHES -> {
                return persist(settings, enabled, parsed, resolvedPassword, redirectAttributes,
                        probe.message());
            }
            case MAPPING_MISMATCH -> {
                // Don't save yet — round-trip the form values back to the page so the
                // operator can confirm or cancel. The existing/expected mappings drive
                // the side-by-side diff in the modal.
                redirectAttributes.addFlashAttribute("ftsMismatch", true);
                redirectAttributes.addFlashAttribute("ftsMismatchMessage", probe.message());
                redirectAttributes.addFlashAttribute("ftsExistingMapping", probe.existingMappingJson());
                redirectAttributes.addFlashAttribute("ftsExpectedMapping", probe.expectedMappingJson());
                redirectAttributes.addFlashAttribute("ftsPendingEnabled", enabled);
                redirectAttributes.addFlashAttribute("ftsPendingEndpoint", parsed.endpoint());
                redirectAttributes.addFlashAttribute("ftsPendingIndexName", parsed.indexName());
                redirectAttributes.addFlashAttribute("ftsPendingUsername",
                        parsed.username() == null ? "" : parsed.username());
                // We do NOT round-trip the password — the form must require the operator
                // to retype it on confirm if they want to change it. Otherwise a stray
                // confirm with no typed password would silently keep whatever was stored.
                redirectAttributes.addFlashAttribute("ftsPendingClearPassword",
                        parsed.clearPassword());
                return "redirect:/admin/general";
            }
            case UNREACHABLE -> {
                redirectAttributes.addFlashAttribute("error",
                        "Could not reach OpenSearch — settings not saved. " + probe.message());
                return "redirect:/admin/general";
            }
            default -> {
                redirectAttributes.addFlashAttribute("error",
                        "OpenSearch returned an error — settings not saved. " + probe.message());
                return "redirect:/admin/general";
            }
        }
    }

    /**
     * Confirm-and-save companion of {@link #saveFullTextSearch}. Reached from the mapping
     * mismatch modal when the operator chooses "Continue with existing index". The
     * settings are written without a second probe — the operator has explicitly accepted
     * the divergent shape, and re-probing here would just race against another possible
     * mismatch reading.
     */
    @PostMapping("/full-text-search/confirm")
    public String confirmFullTextSearch(
            @RequestParam(value = "fullTextSearchEnabled", required = false) final Boolean fullTextSearchEnabled,
            @RequestParam("opensearchEndpoint") final String opensearchEndpoint,
            @RequestParam("opensearchIndexName") final String opensearchIndexName,
            @RequestParam(value = "opensearchUsername", required = false) final String opensearchUsername,
            @RequestParam(value = "opensearchPassword", required = false) final String opensearchPassword,
            @RequestParam(value = "clearOpensearchPassword", required = false) final Boolean clearOpensearchPassword,
            final RedirectAttributes redirectAttributes) {
        final boolean enabled = fullTextSearchEnabled != null && fullTextSearchEnabled;
        final FtsFormResult parsed = parseFtsForm(opensearchEndpoint, opensearchIndexName,
                opensearchUsername, opensearchPassword, clearOpensearchPassword);
        if (parsed.error() != null) {
            redirectAttributes.addFlashAttribute("error", parsed.error());
            return "redirect:/admin/general";
        }
        final GeneralSettings settings = generalSettingsService.load();
        final String resolvedPassword = resolvePassword(settings, parsed);
        return persist(settings, enabled, parsed, resolvedPassword, redirectAttributes,
                "Full text search settings saved (using existing index with non-canonical mapping).");
    }

    private String persist(final GeneralSettings settings, final boolean enabled,
                           final FtsFormResult parsed, final String resolvedPassword,
                           final RedirectAttributes redirectAttributes, final String successMessage) {
        final boolean previousEnabled = settings.isFullTextSearchEnabled();
        final String previousEndpoint = settings.getOpensearchEndpoint();
        final String previousIndex = settings.getOpensearchIndexName();
        final String previousUsername = settings.getOpensearchUsername();
        final boolean hadPassword = settings.getOpensearchPassword() != null
                && !settings.getOpensearchPassword().isBlank();

        settings.setFullTextSearchEnabled(enabled);
        settings.setOpensearchEndpoint(parsed.endpoint());
        settings.setOpensearchIndexName(parsed.indexName());
        settings.setOpensearchUsername(parsed.username());
        settings.setOpensearchPassword(resolvedPassword);
        generalSettingsService.save(settings);

        auditLogService.log("GENERAL_SETTINGS_CHANGE", "Settings", GeneralSettings.SINGLETON_ID,
                Map.of(
                        "previousFullTextSearchEnabled", previousEnabled,
                        "fullTextSearchEnabled", enabled,
                        "previousOpensearchEndpoint", previousEndpoint == null ? "" : previousEndpoint,
                        "opensearchEndpoint", parsed.endpoint(),
                        "previousOpensearchIndexName", previousIndex == null ? "" : previousIndex,
                        "opensearchIndexName", parsed.indexName(),
                        "previousOpensearchUsername", previousUsername == null ? "" : previousUsername,
                        "opensearchUsername", parsed.username() == null ? "" : parsed.username(),
                        "passwordChanged", parsed.passwordChanged(hadPassword),
                        "passwordCleared", parsed.clearPassword()));

        redirectAttributes.addFlashAttribute("success", successMessage);
        return "redirect:/admin/general";
    }

    /** Parse + validate the FTS form once for both the save and confirm endpoints. */
    private static FtsFormResult parseFtsForm(final String endpointRaw, final String indexNameRaw,
                                              final String usernameRaw, final String passwordRaw,
                                              final Boolean clearPasswordRaw) {
        String endpoint = endpointRaw == null ? "" : endpointRaw.trim();
        if (endpoint.isEmpty()) {
            return FtsFormResult.error("OpenSearch endpoint is required.");
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return FtsFormResult.error("OpenSearch endpoint must start with http:// or https://.");
        }
        try {
            URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            return FtsFormResult.error("OpenSearch endpoint is not a valid URI: " + e.getMessage());
        }
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        final String indexName = indexNameRaw == null ? "" : indexNameRaw.trim();
        if (indexName.isEmpty()) {
            return FtsFormResult.error("Index name is required.");
        }
        // OpenSearch index names: lower-case, no spaces, no special chars except - _ +
        // (the cluster will reject invalid names with a 400 anyway, but a fast client-side
        // check produces a friendlier error message).
        if (!indexName.matches("[a-z0-9][a-z0-9\\-_+]*")) {
            return FtsFormResult.error("Index name must be lower-case and contain only "
                    + "letters, digits, '-', '_', or '+'. Received: " + indexName);
        }
        final String username = usernameRaw == null || usernameRaw.isBlank() ? null : usernameRaw.trim();
        final String password = passwordRaw == null ? null : passwordRaw;
        final boolean clear = clearPasswordRaw != null && clearPasswordRaw;
        return new FtsFormResult(endpoint, indexName, username, password, clear, null);
    }

    /**
     * Resolve the password to persist given the form's clear-checkbox + new-password
     * combination. Mirrors the convention used by the data-source admin pages.
     */
    private static String resolvePassword(final GeneralSettings settings, final FtsFormResult parsed) {
        if (parsed.clearPassword()) return null;
        if (parsed.password() != null && !parsed.password().isBlank()) return parsed.password();
        return settings.getOpensearchPassword();
    }

    /**
     * Parsed FTS form values plus a validation error string. Either {@code error} is
     * non-null (and the rest of the fields are unspecified) or every other field is
     * populated.
     */
    private record FtsFormResult(String endpoint, String indexName, String username,
                                 String password, boolean clearPassword, String error) {
        static FtsFormResult error(final String message) {
            return new FtsFormResult(null, null, null, null, false, message);
        }
        boolean passwordChanged(final boolean hadPasswordBefore) {
            // Either the operator typed a new value, or they ticked the clear checkbox.
            // Otherwise the stored password is unchanged.
            return clearPassword
                    ? hadPasswordBefore
                    : (password != null && !password.isBlank());
        }
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
