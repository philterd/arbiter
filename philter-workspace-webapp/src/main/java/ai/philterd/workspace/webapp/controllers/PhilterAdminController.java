/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.philter.model.AdminSettings;
import ai.philterd.philter.model.AuditEvent;
import ai.philterd.philter.model.AuditLogExport;
import ai.philterd.philter.model.GetAuditLogResponse;
import ai.philterd.philter.model.SigningKey;
import ai.philterd.philter.model.UpdateAdminSettingsRequest;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.webapp.security.PhilterAdminService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.ListView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Philter's deployment settings (Admin, Philter, Settings) and Philter's audit log (Audit Log, Philter), with the
 * signed-in administrator's session key, so Philter checks that they are a Philter administrator and its audit
 * log names them. Philter UI's Admin page (Settings and Audit Log tabs) is the reference.
 */
@Controller
public class PhilterAdminController {

    private static final Logger log = LoggerFactory.getLogger(PhilterAdminController.class);

    static final String SETTINGS_PATH = "/admin/philter/settings";
    static final String AUDIT_PATH = "/admin/audit/philter";

    /** Philter's longest export range, in days after the first day. */
    static final int MAXIMUM_EXPORT_DAYS = 30;
    /** The most events Philter returns in one export page. */
    static final int EXPORT_PAGE = 1000;
    /** The most events one download holds, as in Philter UI. */
    static final int MAXIMUM_EXPORT_EVENTS = 100_000;

    static final String NOT_ADMIN = "Philter refused: your Philter user is not a Philter administrator.";
    static final String REFUSED = "Philter did not accept the settings. Each webhook allowlist entry must be a "
            + "hostname, an IP address, or a CIDR range, separated by commas. If another administrator changed "
            + "Philter's settings since you opened this page, reload it and make your change again.";

    private final PhilterAdminService philter;

    public PhilterAdminController(final PhilterAdminService philter) {
        this.philter = philter;
    }

    /** The form's values: Philter's settings as read, or as the administrator submitted them. */
    public record SettingsForm(boolean mfaAvailable, boolean mfaRequired, boolean signingEnabled,
                               boolean diffuseCountsEnabled, String webhookAllowlist, boolean phieldEnabled,
                               String phieldUrl, String phieldSourceId, String phieldOrganization) {

        static SettingsForm of(final AdminSettings s) {
            return new SettingsForm(s.isMfaAvailable(), s.isMfaRequired(), s.isSigningEnabled(),
                    s.isDiffuseCountsEnabled(), nullToEmpty(s.getWebhookAllowlist()), s.isPhieldEnabled(),
                    nullToEmpty(s.getPhieldUrl()), nullToEmpty(s.getPhieldSourceId()),
                    nullToEmpty(s.getPhieldOrganization()));
        }
    }

    // ---- settings

    @GetMapping(SETTINGS_PATH)
    public String settings(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            final AdminSettings settings = philter.settings(person.getEncryptedSessionKey());
            model.addAttribute("form", SettingsForm.of(settings));
            model.addAttribute("settings", settings);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", readError(e, "Philter's settings"));
            return settingsPage(model, authentication);
        }
        signingKey(person, model);
        return settingsPage(model, authentication);
    }

    /**
     * Saves the settings that changed from the ones the form was opened with, so a setting someone else changed
     * meanwhile is not overwritten with an old value. The Phield API key is write-only: blank keeps it.
     */
    @PostMapping(SETTINGS_PATH)
    public String save(@RequestParam(value = "mfaAvailable", defaultValue = "false") final boolean mfaAvailable,
                       @RequestParam(value = "mfaRequired", defaultValue = "false") final boolean mfaRequired,
                       @RequestParam(value = "signingEnabled", defaultValue = "false") final boolean signingEnabled,
                       @RequestParam(value = "diffuseCountsEnabled", defaultValue = "false") final boolean diffuse,
                       @RequestParam(value = "webhookAllowlist", defaultValue = "") final String webhookAllowlist,
                       @RequestParam(value = "phieldEnabled", defaultValue = "false") final boolean phieldEnabled,
                       @RequestParam(value = "phieldUrl", defaultValue = "") final String phieldUrl,
                       @RequestParam(value = "phieldSourceId", defaultValue = "") final String phieldSourceId,
                       @RequestParam(value = "phieldOrganization", defaultValue = "") final String phieldOrganization,
                       @RequestParam(value = "phieldApiKey", defaultValue = "") final String phieldApiKey,
                       @RequestParam(value = "removePhieldApiKey", defaultValue = "false") final boolean removeKey,
                       @RequestParam Map<String, String> params,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final SettingsForm submitted = new SettingsForm(mfaAvailable, mfaRequired, signingEnabled, diffuse,
                allowlist(webhookAllowlist), phieldEnabled, phieldUrl.trim(), phieldSourceId.trim(),
                phieldOrganization.trim());
        final SettingsForm original = original(params);
        final String problem = settingsProblem(submitted);
        if (problem != null) {
            return settingsAgain(person, authentication, model, submitted, original, problem);
        }
        final UpdateAdminSettingsRequest update = new UpdateAdminSettingsRequest();
        final List<String> changed = new ArrayList<>();
        if (submitted.mfaAvailable() != original.mfaAvailable()) {
            update.setMfaAvailable(submitted.mfaAvailable());
            changed.add("mfaAvailable");
        }
        if (submitted.mfaRequired() != original.mfaRequired()) {
            update.setMfaRequired(submitted.mfaRequired());
            changed.add("mfaRequired");
        }
        if (submitted.signingEnabled() != original.signingEnabled()) {
            update.setSigningEnabled(submitted.signingEnabled());
            changed.add("signingEnabled");
        }
        if (submitted.diffuseCountsEnabled() != original.diffuseCountsEnabled()) {
            update.setDiffuseCountsEnabled(submitted.diffuseCountsEnabled());
            changed.add("diffuseCountsEnabled");
        }
        if (!submitted.webhookAllowlist().equals(original.webhookAllowlist())) {
            update.setWebhookAllowlist(submitted.webhookAllowlist());
            changed.add("webhookAllowlist");
        }
        if (submitted.phieldEnabled() != original.phieldEnabled()) {
            update.setPhieldEnabled(submitted.phieldEnabled());
            changed.add("phieldEnabled");
        }
        if (!submitted.phieldUrl().equals(original.phieldUrl())) {
            update.setPhieldUrl(submitted.phieldUrl());
            changed.add("phieldUrl");
        }
        if (!submitted.phieldSourceId().equals(original.phieldSourceId())) {
            update.setPhieldSourceId(submitted.phieldSourceId());
            changed.add("phieldSourceId");
        }
        if (!submitted.phieldOrganization().equals(original.phieldOrganization())) {
            update.setPhieldOrganization(submitted.phieldOrganization());
            changed.add("phieldOrganization");
        }
        // An empty key removes it in Philter; a blank field keeps it.
        if (removeKey) {
            update.setPhieldApiKey("");
            changed.add("phieldApiKey");
        } else if (!phieldApiKey.isBlank()) {
            update.setPhieldApiKey(phieldApiKey.trim());
            changed.add("phieldApiKey");
        }
        if (changed.isEmpty()) {
            flash.addFlashAttribute("success", "Nothing was changed.");
            return "redirect:" + SETTINGS_PATH;
        }
        final AdminSettings saved;
        try {
            saved = philter.update(person.getEncryptedSessionKey(), update);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            final String message = e instanceof PhilterError error && error.getStatusCode() == 403 ? NOT_ADMIN
                    // Philter's message repeats the value it refused, so it is not shown. The checks above cover the
                    // form's own values, which leaves the allowlist, or a setting changed since the page was opened.
                    : e instanceof PhilterError error && error.getStatusCode() == 400 ? REFUSED
                    : FormErrors.from(e).message();
            return settingsAgain(person, authentication, model, submitted, original, message);
        }
        flash.addFlashAttribute("success", "Philter's settings saved.");
        if (saved.getWarnings() != null && !saved.getWarnings().isEmpty()) {
            flash.addFlashAttribute("warnings", saved.getWarnings());
        }
        return "redirect:" + SETTINGS_PATH;
    }

    // ---- audit log

    /** Philter's audit log, newest first, filtered by event type, by days (UTC), and by the acting user. */
    @GetMapping(AUDIT_PATH)
    public String audit(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        ListView list = ListView.of(request, AUDIT_PATH, Set.of(), null, List.of("event", "from", "to", "user"));
        final List<AuditEvent> events = new ArrayList<>();
        final String event = list.filter("event");
        final String user = list.filter("user");
        LocalDate from = null;
        LocalDate to = null;
        String problem = null;
        try {
            from = date(list.filter("from"));
            to = date(list.filter("to"));
        } catch (DateTimeParseException e) {
            problem = "Enter dates as YYYY-MM-DD.";
        }
        if (problem == null && from != null && to != null && from.isAfter(to)) {
            problem = "The first day must not be after the last.";
        }
        if (problem == null) {
            try {
                final GetAuditLogResponse page = philter.events(person.getEncryptedSessionKey(),
                        event == null ? null : event.toLowerCase(Locale.ROOT), startOf(from),
                        to == null ? null : startOf(to.plusDays(1)), user, list.options().search(null));
                if (page.getEvents() != null) {
                    events.addAll(page.getEvents());
                }
                list = list.withTotal(page.getTotal());
            } catch (Exception e) {
                if (e instanceof UnauthorizedException) {
                    return PhilterSessions.end(request);
                }
                problem = auditError(e, user);
            }
        }
        model.addAttribute("error", problem);
        model.addAttribute("list", list);
        model.addAttribute("events", events);
        model.addAttribute("today", LocalDate.now(ZoneOffset.UTC));
        model.addAttribute("monthAgo", LocalDate.now(ZoneOffset.UTC).minusDays(MAXIMUM_EXPORT_DAYS));
        model.addAttribute("maximumExportDays", MAXIMUM_EXPORT_DAYS);
        model.addAttribute("maximumExportEvents", MAXIMUM_EXPORT_EVENTS);
        return "admin-philter-audit";
    }

    /**
     * Philter's audit log for whole days (UTC) as one CSV download, read a page at a time by Philter's cursor so each
     * event is read once, and written as it is read. Stops at {@link #MAXIMUM_EXPORT_EVENTS}.
     */
    @GetMapping(AUDIT_PATH + "/export")
    public ModelAndView export(@RequestParam("from") final String fromText, @RequestParam("to") final String toText,
                               final Authentication authentication, final HttpServletRequest request,
                               final HttpServletResponse response, final RedirectAttributes flash) throws IOException {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return new ModelAndView("redirect:/login");
        }
        final LocalDate from;
        final LocalDate to;
        try {
            from = LocalDate.parse(fromText.trim());
            to = LocalDate.parse(toText.trim());
        } catch (DateTimeParseException e) {
            flash.addFlashAttribute("error", "Enter the export's days as YYYY-MM-DD.");
            return new ModelAndView("redirect:" + AUDIT_PATH);
        }
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAXIMUM_EXPORT_DAYS) {
            flash.addFlashAttribute("error", "The export's last day must be on or after its first, and at most "
                    + MAXIMUM_EXPORT_DAYS + " days after it.");
            return new ModelAndView("redirect:" + AUDIT_PATH);
        }
        final String key = person.getEncryptedSessionKey();
        AuditLogExport page;
        try {
            page = philter.exportPage(key, from, to, null, EXPORT_PAGE);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return new ModelAndView(PhilterSessions.end(request));
            }
            flash.addFlashAttribute("error", e instanceof PhilterError error && error.getStatusCode() == 403
                    ? NOT_ADMIN : FormErrors.from(e).message());
            return new ModelAndView("redirect:" + AUDIT_PATH);
        }
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"philter-audit-" + from + "-to-" + to + ".csv\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        int rows = 0;
        boolean complete = true;
        final OutputStream out = response.getOutputStream();
        boolean first = true;
        while (true) {
            final String csv = page.getCsv() == null ? "" : page.getCsv();
            // Every page starts with the header; the download keeps the first page's.
            out.write((first ? csv : afterFirstRecord(csv)).getBytes(StandardCharsets.UTF_8));
            first = false;
            rows += page.getRows();
            final String next = page.isTruncated() ? page.getNextCursor() : null;
            if (next == null) {
                break;
            }
            if (rows >= MAXIMUM_EXPORT_EVENTS) {
                complete = false;
                break;
            }
            try {
                // The last page asks only for what the cap leaves, so a download holds at most the cap.
                page = philter.exportPage(key, from, to, next, Math.min(EXPORT_PAGE, MAXIMUM_EXPORT_EVENTS - rows));
            } catch (Exception e) {
                // The download has started, so the failure can only end it short; the log says so.
                log.warn("Philter's audit export stopped after {} events: {}", rows, PhilterClients.describe(e));
                complete = false;
                break;
            }
        }
        out.flush();
        if (!complete) {
            log.info("Philter's audit export ended at {} events, short of the whole range.", rows);
        }
        return null;
    }

    // ---- helpers

    private String settingsAgain(final PhilterSessionAuthentication person, final Authentication authentication,
                                 final Model model, final SettingsForm submitted, final SettingsForm original,
                                 final String error) {
        model.addAttribute("form", submitted);
        model.addAttribute("original", original);
        model.addAttribute("error", error);
        try {
            model.addAttribute("settings", philter.settings(person.getEncryptedSessionKey()));
        } catch (Exception e) {
            log.warn("Could not read Philter's settings: {}", PhilterClients.describe(e));
        }
        signingKey(person, model);
        return settingsPage(model, authentication);
    }

    private String settingsPage(final Model model, final Authentication authentication) {
        if (!model.containsAttribute("original") && model.getAttribute("form") != null) {
            model.addAttribute("original", model.getAttribute("form"));
        }
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        return "admin-philter-settings";
    }

    private void signingKey(final PhilterSessionAuthentication person, final Model model) {
        try {
            final SigningKey key = philter.signingKey(person.getEncryptedSessionKey());
            model.addAttribute("signingKey", key);
        } catch (Exception e) {
            log.warn("Could not read Philter's signing key: {}", PhilterClients.describe(e));
        }
    }

    /** The settings the form was opened with, from its hidden fields. */
    private static SettingsForm original(final Map<String, String> params) {
        return new SettingsForm(flag(params, "originalMfaAvailable"), flag(params, "originalMfaRequired"),
                flag(params, "originalSigningEnabled"), flag(params, "originalDiffuseCountsEnabled"),
                allowlist(params.getOrDefault("originalWebhookAllowlist", "")), flag(params, "originalPhieldEnabled"),
                params.getOrDefault("originalPhieldUrl", "").trim(), params.getOrDefault("originalPhieldSourceId", "").trim(),
                params.getOrDefault("originalPhieldOrganization", "").trim());
    }

    private static boolean flag(final Map<String, String> params, final String name) {
        return "true".equals(params.get(name));
    }

    /** What is wrong with the settings before Philter sees them, or null. */
    static String settingsProblem(final SettingsForm form) {
        if (form.mfaRequired() && !form.mfaAvailable()) {
            return "MFA can be required only while it is available.";
        }
        if (!form.phieldUrl().isEmpty() && !isHttpUrl(form.phieldUrl())) {
            return "The Phield URL must be an http or https URL, such as https://phield.example.com.";
        }
        if (form.phieldEnabled() && form.phieldUrl().isEmpty()) {
            return "Enter the Phield URL to publish counts to Phield.";
        }
        return null;
    }

    private static boolean isHttpUrl(final String url) {
        try {
            final URI uri = URI.create(url);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The allowlist as Philter keeps it: entries separated by commas, with lines and spaces around them dropped. */
    static String allowlist(final String text) {
        final List<String> entries = new ArrayList<>();
        for (final String entry : text.split("[,\\r\\n]+")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return String.join(",", entries);
    }

    /** {@code csv} without its first record, the header; a newline inside a quoted field does not end a record. */
    public static String afterFirstRecord(final String csv) {
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            final char c = csv.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\n' && !quoted) {
                return csv.substring(i + 1);
            }
        }
        return "";
    }

    private static LocalDate date(final String text) {
        return text == null ? null : LocalDate.parse(text.trim());
    }

    private static Instant startOf(final LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String auditError(final Exception e, final String user) {
        if (e instanceof PhilterError error) {
            switch (error.getStatusCode()) {
                case 403:
                    return NOT_ADMIN;
                case 404:
                    return user == null ? FormErrors.from(e).message() : "Philter found no such user you can reach. "
                            + "Filtering by another user needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";
                case 400:
                    return "Philter did not accept the filter. The event type must be one Philter records, such as "
                            + "sign_in_failed or settings_updated.";
                default:
                    break;
            }
        }
        log.warn("Could not read Philter's audit log: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so its audit log cannot be shown.";
    }

    private static String readError(final Exception e, final String what) {
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return NOT_ADMIN;
        }
        log.warn("Could not read {}: {}", what, PhilterClients.describe(e));
        return "Philter could not be reached, so " + what + " cannot be shown.";
    }

    private static PhilterSessionAuthentication person(final Authentication authentication) {
        return authentication instanceof PhilterSessionAuthentication philterSession ? philterSession : null;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
