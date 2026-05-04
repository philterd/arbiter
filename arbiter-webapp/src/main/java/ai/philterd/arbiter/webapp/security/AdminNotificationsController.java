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

import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/admin/notifications")
public class AdminNotificationsController {

    private final NotificationSettingsService notificationSettingsService;
    private final AuditLogService auditLogService;

    public AdminNotificationsController(NotificationSettingsService notificationSettingsService,
                                        AuditLogService auditLogService) {
        this.notificationSettingsService = notificationSettingsService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String form(Model model) {
        NotificationSettings settings = notificationSettingsService.load();
        model.addAttribute("settings", settings);
        model.addAttribute("hasPassword", settings.getPassword() != null && !settings.getPassword().isEmpty());
        return "admin-notifications";
    }

    @PostMapping
    public String save(@RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                       @RequestParam(value = "host", required = false) String host,
                       @RequestParam(value = "port", required = false) Integer port,
                       @RequestParam(value = "username", required = false) String username,
                       @RequestParam(value = "password", required = false) String password,
                       @RequestParam(value = "fromAddress", required = false) String fromAddress,
                       @RequestParam(value = "fromName", required = false) String fromName,
                       @RequestParam(value = "useStartTls", defaultValue = "false") boolean useStartTls,
                       @RequestParam(value = "useSsl", defaultValue = "false") boolean useSsl,
                       @RequestParam(value = "clearPassword", defaultValue = "false") boolean clearPassword,
                       RedirectAttributes redirectAttributes) {

        if (port != null && (port < 1 || port > 65535)) {
            redirectAttributes.addFlashAttribute("error", "Port must be between 1 and 65535.");
            return "redirect:/admin/notifications";
        }
        if (useStartTls && useSsl) {
            redirectAttributes.addFlashAttribute("error",
                    "Choose either STARTTLS or implicit SSL/TLS, not both.");
            return "redirect:/admin/notifications";
        }

        NotificationSettings settings = notificationSettingsService.load();
        settings.setEnabled(enabled);
        settings.setHost(trimOrNull(host));
        if (port != null) settings.setPort(port);
        settings.setUsername(trimOrNull(username));
        settings.setFromAddress(trimOrNull(fromAddress));
        settings.setFromName(trimOrNull(fromName));
        settings.setUseStartTls(useStartTls);
        settings.setUseSsl(useSsl);

        boolean passwordChanged = false;
        if (clearPassword) {
            settings.setPassword(null);
            passwordChanged = true;
        } else if (password != null && !password.isEmpty()) {
            settings.setPassword(password);
            passwordChanged = true;
        }

        notificationSettingsService.save(settings);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", enabled);
        details.put("host", settings.getHost() == null ? "" : settings.getHost());
        details.put("port", settings.getPort());
        details.put("username", settings.getUsername() == null ? "" : settings.getUsername());
        details.put("fromAddress", settings.getFromAddress() == null ? "" : settings.getFromAddress());
        details.put("useStartTls", settings.isUseStartTls());
        details.put("useSsl", settings.isUseSsl());
        details.put("passwordChanged", passwordChanged);
        auditLogService.log("NOTIFICATION_SETTINGS_CHANGE", "Settings", NotificationSettings.SINGLETON_ID, details);

        redirectAttributes.addFlashAttribute("success", "Notification settings saved.");
        return "redirect:/admin/notifications";
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
