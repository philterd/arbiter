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

    static final String ADMIN_KEY = "PHILTER_WORKSPACE_PHILTER_API_KEY belongs to a Philter administrator, and Philter Workspace uses "
            + "the key for background work such as ingest. If Philter Workspace's policies and contexts already belong to "
            + "this key's user, keep the user and remove its administrator role in Philter, then narrow the key "
            + "to the redact and policies:read scopes. Otherwise use a key for a Philter user who is not an "
            + "administrator. See the Philter section of Philter Workspace's workflow docs.";

    private final PhilterSettings philter;
    private final AuditLogService auditLogService;
    private final BootstrapKeyWarning bootstrapKeyWarning;

    public AdminPhilterController(final PhilterSettings philter, final AuditLogService auditLogService,
                                  final BootstrapKeyWarning bootstrapKeyWarning) {
        this.philter = philter;
        this.auditLogService = auditLogService;
        this.bootstrapKeyWarning = bootstrapKeyWarning;
    }

    @GetMapping
    public String view(final Authentication authentication, final Model model) {
        model.addAttribute("bootstrapKeyActive", bootstrapKeyWarning.show(authentication));
        model.addAttribute("bootstrapKeyDocs", BootstrapKeyWarning.DOCS_URL);
        model.addAttribute("philterUrl", philter.url());
        model.addAttribute("apiKeyAdmin", philter.apiKeyAdmin());
        model.addAttribute("adminKeyWarning", ADMIN_KEY);
        return "admin-philter";
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
