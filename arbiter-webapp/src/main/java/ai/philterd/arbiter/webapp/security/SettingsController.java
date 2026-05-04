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

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.model.UserSettings;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.UserSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.SecureRandom;
import java.util.Base64;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserSettingsService userSettingsService;

    public SettingsController(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              AuditLogService auditLogService,
                              UserSettingsService userSettingsService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.userSettingsService = userSettingsService;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @GetMapping
    public String settings(Authentication authentication, Model model) {
        boolean hasApiKey = false;
        UserSettings settings = userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName());
        if (authentication != null) {
            User user = userRepository.findByEmail(authentication.getName()).orElse(null);
            hasApiKey = user != null && user.getApiKey() != null && !user.getApiKey().isBlank();
        }
        model.addAttribute("hasApiKey", hasApiKey);
        model.addAttribute("userSettings", settings);
        return "settings";
    }

    @PostMapping("/preferences")
    public String savePreferences(@RequestParam(value = "skipCompletedInReview", defaultValue = "false") boolean skipCompletedInReview,
                                  @RequestParam(value = "advanceToNextOnApprove", defaultValue = "false") boolean advanceToNextOnApprove,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        User user = authentication == null ? null
                : userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getId() == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        UserSettings settings = userSettingsService.loadForUserId(user.getId());
        settings.setUserId(user.getId());
        settings.setSkipCompletedInReview(skipCompletedInReview);
        settings.setAdvanceToNextOnApprove(advanceToNextOnApprove);
        userSettingsService.save(settings);
        auditLogService.log("USER_SETTINGS_CHANGE", "User", user.getId(),
                java.util.Map.of(
                        "skipCompletedInReview", skipCompletedInReview,
                        "advanceToNextOnApprove", advanceToNextOnApprove));
        redirectAttributes.addFlashAttribute("success", "Preferences updated.");
        return "redirect:/settings";
    }

    @PostMapping("/api-key")
    public String generateApiKey(Authentication authentication, RedirectAttributes redirectAttributes) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String apiKey = ENCODER.encodeToString(bytes);
        user.setApiKey(Hashing.sha512Hex(apiKey));
        userRepository.save(user);
        auditLogService.log("API_KEY_GENERATE", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("apiKey", apiKey);
        return "redirect:/settings";
    }

    @PostMapping("/api-key/revoke")
    public String revokeApiKey(Authentication authentication, RedirectAttributes redirectAttributes) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        user.setApiKey(null);
        userRepository.save(user);
        auditLogService.log("API_KEY_REVOKE", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "API key revoked.");
        return "redirect:/settings";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam("currentPassword") String currentPassword,
                                 @RequestParam("newPassword") String newPassword,
                                 @RequestParam("confirmPassword") String confirmPassword,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        if (newPassword == null || newPassword.length() < 4) {
            redirectAttributes.addFlashAttribute("error", "New password must be at least 4 characters.");
            return "redirect:/settings";
        }
        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New password and confirmation do not match.");
            return "redirect:/settings";
        }

        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect.");
            return "redirect:/settings";
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditLogService.log("PASSWORD_CHANGE", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "Password updated.");
        return "redirect:/settings";
    }
}
