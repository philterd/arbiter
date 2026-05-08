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
import ai.philterd.arbiter.service.NotificationSettingsService;
import ai.philterd.arbiter.service.UserNotificationService;
import ai.philterd.arbiter.webapp.security.InvitationService;
import ai.philterd.arbiter.webapp.security.LoginAttemptService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final NotificationSettingsService notificationSettingsService;
    private final UserNotificationService userNotificationService;
    private final LoginAttemptService loginAttemptService;
    private final InvitationService invitationService;

    public AdminController(final UserRepository userRepository,
                           final PasswordEncoder passwordEncoder,
                           final AuditLogService auditLogService,
                           final NotificationSettingsService notificationSettingsService,
                           final UserNotificationService userNotificationService,
                           final LoginAttemptService loginAttemptService,
                           final InvitationService invitationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.notificationSettingsService = notificationSettingsService;
        this.userNotificationService = userNotificationService;
        this.loginAttemptService = loginAttemptService;
        this.invitationService = invitationService;
    }

    @GetMapping("/users")
    public String users(final Model model, final Authentication authentication) {
        final org.springframework.data.domain.Page<User> usersPage =
                userRepository.findAll(PageRequest.of(0, 500, Sort.by("email")));
        final List<User> users = usersPage != null ? usersPage.getContent() : List.of();
        final NotificationSettings notifications = notificationSettingsService.load();
        // Per-row "is locked out" flag, keyed by user id, so the template can show a
        // badge + Unlock button for any account currently in the 15-minute lockout.
        final Map<String, Boolean> lockedByUserId = new HashMap<>();
        for (User u : users) {
            lockedByUserId.put(u.getId(), loginAttemptService.isEmailLocked(u.getEmail()));
        }
        model.addAttribute("users", users);
        model.addAttribute("lockedByUserId", lockedByUserId);
        model.addAttribute("currentEmail", authentication == null ? null : authentication.getName());
        model.addAttribute("notificationsEnabled", notifications.isEnabled());
        return "admin-users";
    }

    /**
     * Issue an invitation for a new user. The recipient sets their own password via the
     * link in the email — no plaintext password is ever typed by the admin, written to a
     * log, or transported over SMTP. Outbound email must be configured: with no SMTP path
     * the recipient could not receive the link, so the create is refused up front.
     */
    @PostMapping("/users")
    public String create(@RequestParam("email") final String email,
                         @RequestParam(value = "admin", defaultValue = "false") final boolean admin,
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
        if (userRepository.findByEmail(trimmed).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Email \"" + trimmed + "\" is already taken.");
            return "redirect:/admin/users";
        }
        final NotificationSettings settings = notificationSettingsService.load();
        if (!settings.isEnabled()) {
            redirectAttributes.addFlashAttribute("error",
                    "Outbound email is not enabled, so an invitation cannot be sent. "
                            + "Configure SMTP under Admin → Notifications first.");
            return "redirect:/admin/users";
        }

        final InvitationService.IssuedInvitation issued =
                invitationService.issue(trimmed, admin, java.util.Set.of());
        final String link = userNotificationService.buildInvitationLink(issued.token());
        final boolean sent = userNotificationService.sendInvitation(trimmed, link);
        if (!sent) {
            redirectAttributes.addFlashAttribute("error",
                    "Invitation could not be sent. Check the SMTP settings under Admin → Notifications.");
            return "redirect:/admin/users";
        }
        auditLogService.log("USER_INVITATION_ISSUED", "Invitation", issued.invitation().getId(),
                Map.of("email", trimmed, "admin", admin));
        redirectAttributes.addFlashAttribute("success",
                "Invitation sent to \"" + trimmed + "\".");
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
            if (newPassword.length() < 12) {
                redirectAttributes.addFlashAttribute("error", "Password must be at least 12 characters.");
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

    @PostMapping("/users/{userId}/unlock")
    public String unlock(@PathVariable final String userId,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        loginAttemptService.unlock(user.getEmail());
        auditLogService.log("USER_UNLOCK", "User", user.getId(),
                Map.of("email", user.getEmail() == null ? "" : user.getEmail(),
                        "actor", authentication == null ? "" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "User \"" + user.getEmail() + "\" unlocked.");
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
