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

import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.InboxService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import ai.philterd.arbiter.service.UserNotificationService;
import ai.philterd.arbiter.service.WelcomeMessage;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import java.util.Comparator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final NotificationSettingsService notificationSettingsService;
    private final UserNotificationService userNotificationService;
    private final InboxService inboxService;

    public AdminController(final UserRepository userRepository,
                           final PasswordEncoder passwordEncoder,
                           final AuditLogService auditLogService,
                           final NotificationSettingsService notificationSettingsService,
                           final UserNotificationService userNotificationService,
                           final InboxService inboxService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.notificationSettingsService = notificationSettingsService;
        this.userNotificationService = userNotificationService;
        this.inboxService = inboxService;
    }

    @GetMapping("/users")
    public String users(final Model model, final Authentication authentication) {
        final org.springframework.data.domain.Page<User> usersPage =
                userRepository.findAll(PageRequest.of(0, 500, Sort.by("email")));
        final List<User> users = usersPage != null ? usersPage.getContent() : List.of();
        final NotificationSettings notifications = notificationSettingsService.load();
        model.addAttribute("users", users);
        model.addAttribute("currentEmail", authentication == null ? null : authentication.getName());
        model.addAttribute("notificationsEnabled", notifications.isEnabled());
        return "admin-users";
    }

    @PostMapping("/users")
    public String create(@RequestParam("email") final String email,
                         @RequestParam("password") final String password,
                         @RequestParam(value = "admin", defaultValue = "false") final boolean admin,
                         @RequestParam(value = "sendEmail", defaultValue = "false") final boolean sendEmail,
                         final RedirectAttributes redirectAttributes) {
        final String trimmed = email == null ? "" : email.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Email address is required.");
            return "redirect:/admin/users";
        }
        if (!isValidEmail(trimmed)) {
            redirectAttributes.addFlashAttribute("error", "\"" + trimmed + "\" is not a valid email address.");
            return "redirect:/admin/users";
        }
        if (password == null || password.length() < 4) {
            redirectAttributes.addFlashAttribute("error", "Password must be at least 4 characters.");
            return "redirect:/admin/users";
        }
        if (userRepository.findByEmail(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Email \"" + trimmed + "\" is already taken.");
            return "redirect:/admin/users";
        }

        final User user = new User();
        user.setCreatedAt(LocalDateTime.now());
        user.setId(UUID.randomUUID().toString());
        user.setEmail(trimmed);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRoles(rolesFor(admin));
        userRepository.save(user);
        inboxService.sendHtml(user.getId(), WelcomeMessage.html(admin));
        auditLogService.log("USER_CREATE", "User", user.getId(),
                Map.of("email", trimmed, "admin", admin, "sendEmail", sendEmail));

        String success = "User \"" + trimmed + "\" created.";
        if (sendEmail) {
            final NotificationSettings settings = notificationSettingsService.load();
            if (!settings.isEnabled()) {
                redirectAttributes.addFlashAttribute("error",
                        "User created, but outbound email is not enabled, so no welcome email was sent.");
                return "redirect:/admin/users";
            }
            final boolean sent = userNotificationService.sendNewUserCredentials(trimmed, password);
            if (sent) {
                auditLogService.log("USER_CREATE_EMAIL_SENT", "User", user.getId(),
                        Map.of("email", trimmed));
                success += " Welcome email sent.";
            } else {
                redirectAttributes.addFlashAttribute("error",
                        "User created, but the welcome email could not be sent. Check the SMTP settings under Admin → Notifications.");
                return "redirect:/admin/users";
            }
        }
        redirectAttributes.addFlashAttribute("success", success);
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{userId}/edit")
    public String edit(@PathVariable final String userId,
                       @RequestParam(value = "admin", defaultValue = "false") final boolean admin,
                       @RequestParam(value = "newPassword", required = false) final String newPassword,
                       final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        final boolean wasAdmin = user.getRoles() != null && user.getRoles().contains(Roles.ADMIN);
        user.setRoles(rolesFor(admin));
        boolean passwordReset = false;
        if (newPassword != null && !newPassword.isEmpty()) {
            if (newPassword.length() < 4) {
                redirectAttributes.addFlashAttribute("error", "Password must be at least 4 characters.");
                return "redirect:/admin/users";
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            passwordReset = true;
        }
        userRepository.save(user);
        auditLogService.log("USER_UPDATE", "User", user.getId(),
                Map.of("email", user.getEmail() == null ? "" : user.getEmail(),
                        "previousAdmin", wasAdmin,
                        "admin", admin,
                        "passwordReset", passwordReset));
        redirectAttributes.addFlashAttribute("success", "User \"" + user.getEmail() + "\" updated.");
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{userId}/delete")
    public String delete(@PathVariable final String userId,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        if (authentication != null && user.getEmail() != null
                && user.getEmail().equals(authentication.getName())) {
            redirectAttributes.addFlashAttribute("error", "You cannot delete your own account.");
            return "redirect:/admin/users";
        }
        userRepository.deleteById(userId);
        auditLogService.log("USER_DELETE", "User", userId,
                Map.of("email", user.getEmail() == null ? "" : user.getEmail()));
        redirectAttributes.addFlashAttribute("success", "User \"" + user.getEmail() + "\" deleted.");
        return "redirect:/admin/users";
    }

    private static Set<String> rolesFor(final boolean admin) {
        return Set.of(admin ? Roles.ADMIN : Roles.USER);
    }

    private static boolean isValidEmail(String value) {
        if (value == null) return false;
        final int at = value.indexOf('@');
        final int lastAt = value.lastIndexOf('@');
        if (at <= 0 || at != lastAt || at == value.length() - 1) return false;
        final String domain = value.substring(at + 1);
        return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".")
                && !value.contains(" ");
    }
}
