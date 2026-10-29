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

import ai.philterd.philter.model.ApiKey;
import ai.philterd.philter.model.ApiKeyScopeDescription;
import ai.philterd.philter.model.GetApiKeysResponse;
import ai.philterd.philter.model.GetWebhookDeliveriesResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.Webhook;
import ai.philterd.philter.model.WebhookDelivery;
import ai.philterd.philter.model.WebhookTestResult;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.SignInThrottledException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.webapp.security.MfaQrCodes;
import ai.philterd.workspace.webapp.security.PhilterAccountService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.security.PhilterSignInService;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.ListView;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The person's own Philter account on the Settings page: setting up and removing MFA, their long-lived API keys
 * (narrowing a key's scopes and revoking it), and their webhook with its deliveries. Everything is called with the
 * person's session key. Philter does not let a session key create a key or widen one, so creating keys stays in
 * Philter's API. Philter UI's My Account page is the reference.
 */
@Controller
public class PhilterAccountController {

    private static final Logger log = LoggerFactory.getLogger(PhilterAccountController.class);

    static final String KEYS_PATH = "/settings/api-keys";
    static final String WEBHOOK_PATH = "/settings/webhook";
    static final String MFA_PATH = "/settings/mfa";

    /** Philter's shortest webhook secret. */
    static final int MINIMUM_SECRET_LENGTH = 16;
    /** Keys read per page to find one key; Philter pages them 100 at most. */
    private static final int MAXIMUM_KEYS = 100;

    private final PhilterAccountService account;
    private final PhilterSignInService signInService;
    private final PhilterSettings philter;
    private final MfaQrCodes qrCodes;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    public PhilterAccountController(final PhilterAccountService account, final PhilterSignInService signInService,
                                    final PhilterSettings philter, final MfaQrCodes qrCodes,
                                    final AuditLogService auditLogService, final UserRepository userRepository) {
        this.account = account;
        this.signInService = signInService;
        this.philter = philter;
        this.qrCodes = qrCodes;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
    }

    /**
     * One key in the list. {@code workspace} is true for the key Philter Workspace itself uses for batches, matched
     * by its prefix, since Philter lists a key's first 12 characters only.
     */
    public record KeyRow(ApiKey key, boolean workspace, Confirmation revocation) {
        public String scopesUrl() { return KEYS_PATH + "/scopes?id=" + encode(key.getId()); }
    }

    // ---- MFA

    /** The first step of setting up MFA: the person's current password. */
    @GetMapping(MFA_PATH)
    public String mfa() {
        return "settings-mfa";
    }

    /** Starts enrollment with the current password and shows the QR code once. */
    @PostMapping(MFA_PATH + "/start")
    public String startMfa(@RequestParam("currentPassword") final String currentPassword,
                           final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final MfaEnrollment enrollment;
        try {
            enrollment = signInService.startMfaEnrollment(person.getEncryptedSessionKey(), currentPassword);
        } catch (SignInThrottledException e) {
            model.addAttribute("error", "Your sign-in is locked after repeated wrong passwords. Try again later, or "
                    + "ask an administrator to unlock it.");
            return "settings-mfa";
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", FormErrors.from(e, Map.of(
                    ErrorReasons.WRONG_PASSWORD, "The password is not correct.",
                    ErrorReasons.MFA_UNAVAILABLE, "Philter does not offer MFA. An administrator turns it on in "
                            + "Philter's settings.",
                    ErrorReasons.MFA_ALREADY_ENROLLED, "MFA is already set up for you."), Map.of()).message());
            return "settings-mfa";
        }
        model.addAttribute("qrCodeDataUri", qrCodes.dataUri(enrollment.getOtpauthUri()));
        model.addAttribute("secret", enrollment.getSecret());
        return "settings-mfa";
    }

    /** Confirms enrollment with a code. Philter then ends the person's sessions, so they sign in again with a code. */
    @PostMapping(MFA_PATH + "/confirm")
    public String confirmMfa(@RequestParam("code") final String code, final Authentication authentication,
                             final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            signInService.confirmMfaEnrollment(person.getEncryptedSessionKey(), code.trim());
            audit(person, "MFA_ENROLL", Map.of());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", "The code was not accepted. Start again with your password and scan the "
                    + "new QR code.");
            return "settings-mfa";
        }
        request.getSession().invalidate();
        return "redirect:/login?mfaEnrolled";
    }

    /** Removes the person's MFA, with a current code. */
    @PostMapping(MFA_PATH + "/remove")
    public String removeMfa(@RequestParam("code") final String code, final Authentication authentication,
                            final HttpServletRequest request, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            account.removeMfa(person.getEncryptedSessionKey(), code.trim());
            audit(person, "MFA_REMOVE", Map.of());
            flash.addFlashAttribute("success", "Multi-factor authentication removed.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", FormErrors.from(e, Map.of(
                    ErrorReasons.INVALID_CODE, "The code is not valid. A wrong code counts toward locking your MFA.",
                    ErrorReasons.MFA_LOCKED, "Your MFA is locked after repeated wrong codes. Ask an administrator "
                            + "to unlock it.",
                    ErrorReasons.MFA_NOT_ENROLLED, "MFA is not set up for you."), Map.of()).message());
        }
        return "redirect:/settings";
    }

    // ---- API keys

    @GetMapping(KEYS_PATH)
    public String apiKeys(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        ListView list = ListView.of(request, KEYS_PATH, Set.of("created"), "created", List.of());
        final List<KeyRow> rows = new ArrayList<>();
        try {
            final GetApiKeysResponse page = account.apiKeys(person.getEncryptedSessionKey(), list.options());
            if (page.getApiKeys() != null) {
                page.getApiKeys().forEach(k -> rows.add(row(k)));
            }
            list = list.withTotal(page.getTotal());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", readError(e, "your API keys"));
        }
        model.addAttribute("activeTab", "api-keys");
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        return "settings-api-keys";
    }

    /** The scopes form: the key's scopes, any of which can be cleared. Philter refuses adding one from a session. */
    @GetMapping(KEYS_PATH + "/scopes")
    public String scopesForm(@RequestParam("id") final String id, final Authentication authentication,
                             final HttpServletRequest request, final Model model, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final ApiKey key;
        final List<ApiKeyScopeDescription> catalog;
        try {
            key = findKey(person.getEncryptedSessionKey(), id);
            catalog = account.scopes(person.getEncryptedSessionKey());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, "the key"));
            return "redirect:" + KEYS_PATH;
        }
        if (key == null) {
            flash.addFlashAttribute("error", "Philter has no such key of yours. It may have been revoked.");
            return "redirect:" + KEYS_PATH;
        }
        return scopesPage(model, key, catalog, null);
    }

    @PostMapping(KEYS_PATH + "/scopes")
    public String saveScopes(@RequestParam("id") final String id,
                             @RequestParam(value = "scopes", required = false) final List<String> scopes,
                             final Authentication authentication, final HttpServletRequest request,
                             final Model model, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String key = person.getEncryptedSessionKey();
        final List<String> chosen = scopes == null ? List.of() : scopes.stream().distinct().toList();
        try {
            if (chosen.isEmpty()) {
                final ApiKey current = findKey(key, id);
                if (current == null) {
                    flash.addFlashAttribute("error", "Philter has no such key of yours. It may have been revoked.");
                    return "redirect:" + KEYS_PATH;
                }
                return scopesPage(model, current, account.scopes(key),
                        FormErrors.of("A key keeps at least one scope. To stop it working, revoke it."));
            }
            final ApiKey saved = account.setScopes(key, id, chosen);
            audit(person, "API_KEY_SCOPES_CHANGE", Map.of("keyId", id, "scopes", String.join(",", chosen)));
            flash.addFlashAttribute("success", "Scopes of " + saved.getPrefix() + " changed.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", FormErrors.from(e, Map.of(
                    ErrorReasons.SCOPE_NOT_HELD, "Philter Workspace can only remove scopes from a key. To add one, "
                            + "use Philter's API with a long-lived key.",
                    ErrorReasons.SELF_ACTION_REFUSED, "This is the key making the request."), Map.of()).message());
        }
        return "redirect:" + KEYS_PATH;
    }

    @GetMapping(KEYS_PATH + "/revoke")
    public String confirmRevoke(@RequestParam("id") final String id, final Authentication authentication,
                                final HttpServletRequest request, final Model model, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            final ApiKey key = findKey(person.getEncryptedSessionKey(), id);
            if (key == null) {
                flash.addFlashAttribute("error", "Philter has no such key of yours. It may have been revoked.");
                return "redirect:" + KEYS_PATH;
            }
            model.addAttribute("confirmation", row(key).revocation());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, "the key"));
            return "redirect:" + KEYS_PATH;
        }
        return "confirm";
    }

    @PostMapping(KEYS_PATH + "/revoke")
    public String revoke(@RequestParam("id") final String id, final Authentication authentication,
                         final HttpServletRequest request, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            account.revoke(person.getEncryptedSessionKey(), id);
            audit(person, "API_KEY_REVOKE", Map.of("keyId", id));
            flash.addFlashAttribute("success", "Key revoked.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", FormErrors.from(e, Map.of(
                    ErrorReasons.SELF_ACTION_REFUSED, "This is the key making the request."), Map.of()).message());
        }
        return "redirect:" + KEYS_PATH;
    }

    // ---- webhook

    @GetMapping(WEBHOOK_PATH)
    public String webhook(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String key = person.getEncryptedSessionKey();
        ListView list = ListView.of(request, WEBHOOK_PATH, Set.of(), null, List.of());
        final List<WebhookDelivery> deliveries = new ArrayList<>();
        Webhook webhook = null;
        try {
            webhook = account.webhook(key);
            final GetWebhookDeliveriesResponse page = account.deliveries(key, list.options());
            if (page.getDeliveries() != null) {
                deliveries.addAll(page.getDeliveries());
            }
            list = list.withTotal(page.getTotal());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", readError(e, "your webhook"));
        }
        final boolean set = webhook != null && webhook.getUrl() != null && !webhook.getUrl().isBlank();
        if (!model.containsAttribute("url")) {
            model.addAttribute("url", set ? webhook.getUrl() : "");
        }
        return webhookPage(model, list, deliveries, set, set && webhook.isSecretSet());
    }

    @PostMapping(WEBHOOK_PATH)
    public String saveWebhook(@RequestParam("url") final String url,
                              @RequestParam(value = "secret", defaultValue = "") final String secret,
                              @RequestParam(value = "secretSet", defaultValue = "false") final boolean secretSet,
                              final Authentication authentication, final HttpServletRequest request,
                              final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String trimmedUrl = url == null ? "" : url.trim();
        final String trimmedSecret = secret.trim();
        String problem = urlProblem(trimmedUrl);
        if (problem == null && trimmedSecret.isEmpty() && !secretSet) {
            problem = "Enter a secret of at least " + MINIMUM_SECRET_LENGTH + " characters, or generate one.";
        } else if (problem == null && !trimmedSecret.isEmpty() && trimmedSecret.length() < MINIMUM_SECRET_LENGTH) {
            problem = "The secret must be at least " + MINIMUM_SECRET_LENGTH + " characters.";
        }
        if (problem == null) {
            try {
                account.setWebhook(person.getEncryptedSessionKey(), trimmedUrl,
                        trimmedSecret.isEmpty() ? null : trimmedSecret);
                // Neither the URL nor the secret is recorded, as in Philter's own audit log.
                audit(person, "WEBHOOK_SET", Map.of("secretChanged", !trimmedSecret.isEmpty()));
                flash.addFlashAttribute("success", trimmedSecret.isEmpty() ? "Webhook saved."
                        : "Webhook saved. Philter does not show the secret again; make sure the receiving service has it.");
                return "redirect:" + WEBHOOK_PATH;
            } catch (Exception e) {
                if (e instanceof UnauthorizedException) {
                    return PhilterSessions.end(request);
                }
                // The checks above leave the destination allowlist as the reason Philter refuses (its message
                // is not shown, see FormErrors).
                problem = e instanceof PhilterError error && error.getStatusCode() == 400
                        ? "Philter did not accept this URL. Its host must be allowed by Philter's webhook destination "
                                + "allowlist, which refuses private and loopback addresses when it is empty."
                        : FormErrors.from(e).message();
            }
        }
        // The URL is kept for another try; the secret is not put back into the page.
        flash.addFlashAttribute("error", problem);
        flash.addFlashAttribute("url", trimmedUrl);
        return "redirect:" + WEBHOOK_PATH;
    }

    @GetMapping(WEBHOOK_PATH + "/remove")
    public String confirmRemoveWebhook(final Model model) {
        model.addAttribute("confirmation", webhookRemoval());
        return "confirm";
    }

    @PostMapping(WEBHOOK_PATH + "/remove")
    public String removeWebhook(final Authentication authentication, final HttpServletRequest request,
                                final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            account.removeWebhook(person.getEncryptedSessionKey());
            audit(person, "WEBHOOK_REMOVE", Map.of());
            flash.addFlashAttribute("success", "Webhook removed.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", FormErrors.from(e).message());
        }
        return "redirect:" + WEBHOOK_PATH;
    }

    /** Sends Philter's test event now and says what the receiver answered. */
    @PostMapping(WEBHOOK_PATH + "/test")
    public String testWebhook(final Authentication authentication, final HttpServletRequest request,
                              final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            final WebhookTestResult result = account.testWebhook(person.getEncryptedSessionKey());
            flash.addFlashAttribute(result.isDelivered() ? "success" : "error", testOutcome(result));
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", FormErrors.from(e, Map.of(
                    ErrorReasons.WEBHOOK_NOT_SET, "No webhook is set, so there is nothing to test."), Map.of()).message());
        }
        return "redirect:" + WEBHOOK_PATH;
    }

    // ---- helpers

    /** Records a change to the person's own Philter account in Philter Workspace's audit log; Philter audits it too. */
    private void audit(final PhilterSessionAuthentication person, final String action, final Map<String, Object> details) {
        final Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("method", "philter");
        final String recordId = userRepository.findByEmail(person.getName()).map(u -> u.getId()).orElse(null);
        auditLogService.log(action, "User", recordId, all);
    }

    static Confirmation webhookRemoval() {
        return new Confirmation("Remove your webhook?",
                "Philter stops calling it when your asynchronous redactions finish, and its secret is deleted.",
                List.of(), WEBHOOK_PATH + "/remove", "Remove", WEBHOOK_PATH + "/remove", WEBHOOK_PATH, Map.of());
    }

    private KeyRow row(final ApiKey key) {
        final boolean workspace = isWorkspaceKey(key);
        final StringBuilder message = new StringBuilder("It stops working at once and cannot be restored.");
        if (workspace) {
            message.append(" It is the key Philter Workspace uses for batches (PHILTER_WORKSPACE_PHILTER_API_KEY), so "
                    + "ingest and batch redaction stop until it is replaced.");
        }
        if (key.isBootstrap()) {
            message.append(" It is Philter's bootstrap key (PHILTER_BOOTSTRAP_API_KEY), which Philter does not "
                    + "recreate, so anything still using it stops working.");
        }
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", key.getId());
        final Confirmation revocation = new Confirmation("Revoke the key " + key.getPrefix() + "?", message.toString(),
                List.of(), KEYS_PATH + "/revoke", "Revoke", KEYS_PATH + "/revoke?id=" + encode(key.getId()),
                KEYS_PATH, fields);
        return new KeyRow(key, workspace, revocation);
    }

    /** True when {@code key} is Philter Workspace's own, compared by the 12-character prefix Philter lists. */
    boolean isWorkspaceKey(final ApiKey key) {
        final String own = philter.apiKey();
        final String prefix = key.getPrefix();
        if (own == null || own.length() < 12 || prefix == null) {
            return false;
        }
        return prefix.equals(own.substring(0, 12) + "...");
    }

    /** The person's key with this ID, read a page at a time, since Philter has no call for one key. */
    private ApiKey findKey(final String sessionKey, final String id) throws java.io.IOException {
        int offset = 0;
        while (true) {
            final GetApiKeysResponse keys = account.apiKeys(sessionKey,
                    new ListOptions().offset(offset).limit(MAXIMUM_KEYS));
            final List<ApiKey> page = keys.getApiKeys() == null ? List.of() : keys.getApiKeys();
            for (final ApiKey key : page) {
                if (id.equals(key.getId())) {
                    return key;
                }
            }
            offset += page.size();
            if (page.isEmpty() || offset >= keys.getTotal()) {
                return null;
            }
        }
    }

    private String scopesPage(final Model model, final ApiKey key, final List<ApiKeyScopeDescription> catalog,
                              final FormErrors errors) {
        // Only the key's own scopes are offered: Philter refuses a session key adding one.
        final Map<String, String> descriptions = new LinkedHashMap<>();
        catalog.forEach(s -> descriptions.put(s.getName(), s.getDescription()));
        final Map<String, String> offered = new LinkedHashMap<>();
        for (final String scope : key.getScopes() == null ? List.<String>of() : key.getScopes()) {
            offered.put(scope, descriptions.getOrDefault(scope, ""));
        }
        model.addAttribute("key", key);
        model.addAttribute("workspace", isWorkspaceKey(key));
        model.addAttribute("offered", offered);
        model.addAttribute("errors", errors);
        return "settings-api-key-scopes";
    }

    private String webhookPage(final Model model, final ListView list, final List<WebhookDelivery> deliveries,
                               final boolean set, final boolean secretSet) {
        model.addAttribute("activeTab", "webhook");
        model.addAttribute("list", list);
        model.addAttribute("deliveries", deliveries);
        model.addAttribute("webhookSet", set);
        model.addAttribute("secretSet", secretSet);
        model.addAttribute("minimumSecretLength", MINIMUM_SECRET_LENGTH);
        model.addAttribute("removal", webhookRemoval());
        return "settings-webhook";
    }

    /** What is wrong with a webhook URL before Philter sees it, or null. */
    static String urlProblem(final String url) {
        if (url.isEmpty()) {
            return "Enter the URL Philter should call.";
        }
        try {
            final URI uri = URI.create(url);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"https".equals(scheme) && !"http".equals(scheme)) || uri.getHost() == null) {
                return "Enter an http or https URL, such as https://hooks.example.com/philter.";
            }
        } catch (IllegalArgumentException e) {
            return "Enter an http or https URL, such as https://hooks.example.com/philter.";
        }
        return null;
    }

    /** What the receiver answered to the test event. Philter's error text describes the receiver's answer. */
    static String testOutcome(final WebhookTestResult result) {
        if (result.isDelivered()) {
            return "The test event was delivered" + (result.getStatusCode() == null ? "" : " (HTTP "
                    + result.getStatusCode() + ")") + " in " + result.getDurationMillis() + " ms.";
        }
        final String answer = result.getStatusCode() != null ? "the receiver answered HTTP " + result.getStatusCode()
                : result.getError() != null ? result.getError() : "no answer";
        return "The test event was not delivered: " + answer + ".";
    }

    private static String readError(final Exception e, final String what) {
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your session cannot read " + what + ".";
        }
        log.warn("Could not read a Philter account's settings: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so " + what + " cannot be shown.";
    }

    private static PhilterSessionAuthentication person(final Authentication authentication) {
        return authentication instanceof PhilterSessionAuthentication philterSession ? philterSession : null;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
