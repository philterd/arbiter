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
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.webapp.security.BootstrapKeyWarning;
import ai.philterd.workspace.webapp.security.PhilterVersions;
import ai.philterd.workspace.webapp.security.PhilterWebhookSettings;
import ai.philterd.philter.model.Webhook;
import ai.philterd.philter.model.WebhookTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.philterd.philter.model.StatusResponse;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Admin → Philter: the Philter this Philter Workspace uses, which is set in the environment, and a test of the
 * connection and of {@code PHILTER_WORKSPACE_PHILTER_API_KEY}. Nothing here changes the connection.
 */
@Controller
@RequestMapping("/admin/philter")
public class AdminPhilterController {

    private static final Logger log = LoggerFactory.getLogger(AdminPhilterController.class);

    static final String WEBHOOK_SCOPES = "PHILTER_WORKSPACE_PHILTER_API_KEY needs the webhooks:read and "
            + "webhooks:write scopes in Philter to read or set its webhook.";
    static final String WEBHOOK_REFUSED = "Philter refused the webhook URL. Its host must be one Philter may "
            + "deliver to: add it to the webhook allowlist under Admin, Philter, Settings when Philter Workspace is on "
            + "a private network.";
    static final String WEBHOOK_NONE = "Philter has no webhook for this API key's user. Register it first.";

    static final String ADMIN_KEY = "PHILTER_WORKSPACE_PHILTER_API_KEY belongs to a Philter administrator, and Philter Workspace uses "
            + "the key for background work such as ingest. If Philter Workspace's policies and contexts already belong to "
            + "this key's user, keep the user and remove its administrator role in Philter, then narrow the key "
            + "to the redact and policies:read scopes. Otherwise use a key for a Philter user who is not an "
            + "administrator. See the Philter section of Philter Workspace's workflow docs.";

    private final PhilterSettings philter;
    private final AuditLogService auditLogService;
    private final BootstrapKeyWarning bootstrapKeyWarning;
    private final PhilterWebhookSettings webhook;

    public AdminPhilterController(final PhilterSettings philter, final AuditLogService auditLogService,
                                  final BootstrapKeyWarning bootstrapKeyWarning, final PhilterWebhookSettings webhook) {
        this.philter = philter;
        this.auditLogService = auditLogService;
        this.bootstrapKeyWarning = bootstrapKeyWarning;
        this.webhook = webhook;
    }

    @GetMapping
    public String view(final Authentication authentication, final Model model) {
        model.addAttribute("bootstrapKeyActive", bootstrapKeyWarning.show(authentication));
        model.addAttribute("bootstrapKeyDocs", BootstrapKeyWarning.DOCS_URL);
        model.addAttribute("philterUrl", philter.url());
        model.addAttribute("apiKeyAdmin", philter.apiKeyAdmin());
        model.addAttribute("adminKeyWarning", ADMIN_KEY);
        model.addAttribute("webhookEnabled", webhook.enabled());
        model.addAttribute("webhookUrl", webhook.url());
        model.addAttribute("webhookRegistrable", webhook.registrable());
        if (webhook.enabled()) {
            try {
                final Webhook registered = philter.client().getWebhook();
                final String registeredUrl = registered == null ? null : registered.getUrl();
                model.addAttribute("philterWebhookUrl", registeredUrl);
                model.addAttribute("philterWebhookMatches", registeredUrl != null && registered.isSecretSet()
                        && registeredUrl.equals(webhook.url()));
            } catch (Exception e) {
                log.warn("Could not read Philter's webhook: {}", PhilterClients.describe(e));
                model.addAttribute("philterWebhookError", webhookError(e));
            }
        }
        return "admin-philter";
    }

    /** Set this Philter Workspace's URL and secret as the webhook of the API key's user in Philter. */
    @PostMapping("/webhook/register")
    public String registerWebhook(final RedirectAttributes redirectAttributes) {
        if (!webhook.registrable()) {
            redirectAttributes.addFlashAttribute("error", "Set PHILTER_WORKSPACE_PHILTER_WEBHOOK_URL and "
                    + "PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET in Philter Workspace's environment first.");
            return "redirect:/admin/philter";
        }
        boolean ok = false;
        int status = 0;
        try {
            webhook.register(philter.client());
            ok = true;
            redirectAttributes.addFlashAttribute("success", "Philter will send its webhooks to " + webhook.url()
                    + ". Send a test event to check that they arrive.");
        } catch (Exception e) {
            if (e instanceof PhilterError error) {
                status = error.getStatusCode();
            }
            log.warn("Could not register the webhook with Philter: {}", PhilterClients.describe(e));
            redirectAttributes.addFlashAttribute("error", webhookError(e));
        }
        auditLogService.log("PHILTER_WEBHOOK_REGISTER", "Philter", null,
                Map.of("url", webhook.url(), "ok", ok, "status", status));
        return "redirect:/admin/philter";
    }

    /** Ask Philter to send its test event to the webhook now. */
    @PostMapping("/webhook/test")
    public String testWebhook(final RedirectAttributes redirectAttributes) {
        boolean delivered = false;
        int status = 0;
        try {
            final WebhookTestResult result = philter.client().testWebhook();
            delivered = result != null && result.isDelivered();
            status = result == null || result.getStatusCode() == null ? 0 : result.getStatusCode();
            if (delivered) {
                redirectAttributes.addFlashAttribute("success", "Philter delivered a test event to Philter Workspace in "
                        + result.getDurationMillis() + " ms.");
            } else if (status == 401) {
                redirectAttributes.addFlashAttribute("error", "Philter Workspace refused the test event's signature: "
                        + "the secret Philter holds is not PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET. Register the "
                        + "webhook again.");
            } else if (status != 0) {
                redirectAttributes.addFlashAttribute("error", "The webhook URL answered the test event with HTTP "
                        + status + ". Check that it is Philter Workspace's " + ai.philterd.workspace.api.controller
                        .PhilterWebhookController.PATH + ".");
            } else {
                log.warn("Philter could not deliver its webhook test event: {}",
                        result == null ? "no result" : result.getError());
                redirectAttributes.addFlashAttribute("error", "Philter could not reach the webhook URL. Check that "
                        + "Philter can connect to it, and that its host is in Philter's webhook allowlist when "
                        + "Philter Workspace is on a private network.");
            }
        } catch (Exception e) {
            if (e instanceof PhilterError error) {
                status = error.getStatusCode();
            }
            log.warn("Could not send Philter's webhook test event: {}", PhilterClients.describe(e));
            redirectAttributes.addFlashAttribute("error", webhookError(e));
        }
        auditLogService.log("PHILTER_WEBHOOK_TEST", "Philter", null, Map.of("delivered", delivered, "status", status));
        return "redirect:/admin/philter";
    }

    /** Philter Workspace's own message for a refused webhook call, never Philter's text. */
    static String webhookError(final Exception e) {
        if (e instanceof PhilterError error) {
            switch (error.getStatusCode()) {
                case 401:
                    return "Philter rejected PHILTER_WORKSPACE_PHILTER_API_KEY.";
                case 403:
                    return WEBHOOK_SCOPES;
                case 400:
                    return WEBHOOK_REFUSED;
                case 409:
                    return WEBHOOK_NONE;
                default:
                    break;
            }
        }
        return "Philter could not be reached, or did not answer as expected. Try again.";
    }

    @PostMapping("/test")
    public String test(final RedirectAttributes redirectAttributes) {
        boolean ok = false;
        int status = 0;
        String detail;
        try {
            final ai.philterd.philter.PhilterClient client = philter.client();
            final StatusResponse health = client.health();
            detail = "Philter " + (health == null || health.getApplicationVersion() == null
                    ? "(unknown version)" : health.getApplicationVersion());
            final String unsupported = health == null ? null : PhilterVersions.unsupported(health.getApplicationVersion());
            if (unsupported != null) {
                detail = unsupported;
            } else {
                // health() is unauthenticated; the role check accepts any valid key, whatever its scopes.
                final Boolean adminKey = philter.checkKeyRole();
                if (Boolean.TRUE.equals(adminKey)) {
                    detail += ", but the API key belongs to a Philter administrator";
                } else if (adminKey == null) {
                    detail += ", API key accepted, but Philter did not report its role";
                    ok = true;
                } else {
                    detail += ", API key accepted";
                    ok = true;
                }
            }
        } catch (UnauthorizedException e) {
            status = e.getStatusCode();
            detail = "API key rejected (" + PhilterClients.describe(e) + ")";
        } catch (Exception e) {
            if (e instanceof PhilterError error) {
                status = error.getStatusCode();
            }
            detail = PhilterClients.describe(e);
        }

        auditLogService.log("PHILTER_TEST", "Philter", null,
                Map.of("url", philter.url(), "ok", ok, "status", status, "detail", detail));

        if (ok) {
            redirectAttributes.addFlashAttribute("success",
                    "Philter responded (" + detail + ") at " + philter.url() + ".");
        } else {
            redirectAttributes.addFlashAttribute("error",
                    "Philter failed the test at " + philter.url() + ": " + detail);
        }
        return "redirect:/admin/philter";
    }
}
