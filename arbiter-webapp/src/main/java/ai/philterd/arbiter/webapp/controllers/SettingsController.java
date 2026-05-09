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

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.model.UserSettings;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.ApiKeyHashingService;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.security.TotpService;
import jakarta.servlet.http.HttpSession;
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

    private static final String TOTP_SETUP_SECRET = "TOTP_SETUP_SECRET";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserSettingsService userSettingsService;
    private final TotpService totpService;
    private final ApiKeyHashingService apiKeyHashingService;

    public SettingsController(final UserRepository userRepository,
                              final PasswordEncoder passwordEncoder,
                              final AuditLogService auditLogService,
                              final UserSettingsService userSettingsService,
                              final TotpService totpService,
                              final ApiKeyHashingService apiKeyHashingService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.userSettingsService = userSettingsService;
        this.totpService = totpService;
        this.apiKeyHashingService = apiKeyHashingService;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @GetMapping
    public String settings(final Authentication authentication, final Model model) {
        boolean hasApiKey = false;
        boolean mfaEnabled = false;
        final UserSettings settings = userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName());
        if (authentication != null) {
            final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
            hasApiKey = user != null && user.getApiKey() != null && !user.getApiKey().isBlank();
            mfaEnabled = user != null && user.isMfaEnabled();
        }
        model.addAttribute("hasApiKey", hasApiKey);
        model.addAttribute("mfaEnabled", mfaEnabled);
        model.addAttribute("userSettings", settings);
        return "settings";
    }

    @GetMapping("/mfa/setup")
    public String mfaSetup(final Authentication authentication,
                           final HttpSession session,
                           final Model model) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) return "redirect:/settings";

        final String secret = totpService.generateSecret();
        session.setAttribute(TOTP_SETUP_SECRET, secret);
        model.addAttribute("qrCodeDataUri", totpService.qrCodeDataUri(authentication.getName(), secret));
        model.addAttribute("secret", secret);
        return "mfa-setup";
    }

    @PostMapping("/mfa/enable")
    public String mfaEnable(@RequestParam("code") final String code,
                            @RequestParam(value = "required", defaultValue = "false") final boolean required,
                            final Authentication authentication,
                            final HttpSession session,
                            final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        final String secret = (String) session.getAttribute(TOTP_SETUP_SECRET);
        if (secret == null) {
            redirectAttributes.addFlashAttribute("error", "Setup session expired. Please try again.");
            return required ? "redirect:/settings/mfa/setup?required=true" : "redirect:/settings/mfa/setup";
        }
        if (!totpService.verify(secret, code)) {
            redirectAttributes.addFlashAttribute("error", "Invalid code. Please scan the QR code again and try once more.");
            return required ? "redirect:/settings/mfa/setup?required=true" : "redirect:/settings/mfa/setup";
        }
        user.setTotpSecret(secret);
        user.setMfaEnabled(true);
        userRepository.save(user);
        session.removeAttribute(TOTP_SETUP_SECRET);
        auditLogService.log("MFA_ENABLED", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "Two-factor authentication enabled.");
        return "redirect:/settings";
    }

    @PostMapping("/mfa/disable")
    public String mfaDisable(@RequestParam("code") final String code,
                             final Authentication authentication,
                             final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        if (!user.isMfaEnabled()) {
            redirectAttributes.addFlashAttribute("error", "MFA is not currently enabled.");
            return "redirect:/settings";
        }
        if (!totpService.verify(user.getTotpSecret(), code)) {
            redirectAttributes.addFlashAttribute("error", "Invalid code. MFA was not disabled.");
            return "redirect:/settings";
        }
        user.setTotpSecret(null);
        user.setMfaEnabled(false);
        userRepository.save(user);
        auditLogService.log("MFA_DISABLED", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "Two-factor authentication disabled.");
        return "redirect:/settings";
    }

    @PostMapping("/preferences")
    public String savePreferences(@RequestParam(value = "skipCompletedInReview", defaultValue = "false") final boolean skipCompletedInReview,
                                  @RequestParam(value = "advanceToNextOnApprove", defaultValue = "false") final boolean advanceToNextOnApprove,
                                  @RequestParam(value = "reviewSortBy", required = false) final String reviewSortBy,
                                  final Authentication authentication,
                                  final RedirectAttributes redirectAttributes) {
        final User user = authentication == null ? null
                : userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getId() == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        final String resolvedSortBy = UserSettings.isValidReviewSortBy(reviewSortBy)
                ? reviewSortBy : UserSettings.SORT_RISK_SCORE;
        final UserSettings settings = userSettingsService.loadForUserId(user.getId());
        settings.setUserId(user.getId());
        settings.setSkipCompletedInReview(skipCompletedInReview);
        settings.setAdvanceToNextOnApprove(advanceToNextOnApprove);
        settings.setReviewSortBy(resolvedSortBy);
        userSettingsService.save(settings);
        auditLogService.log("USER_SETTINGS_CHANGE", "User", user.getId(),
                java.util.Map.of(
                        "skipCompletedInReview", skipCompletedInReview,
                        "advanceToNextOnApprove", advanceToNextOnApprove,
                        "reviewSortBy", resolvedSortBy));
        redirectAttributes.addFlashAttribute("success", "Preferences updated.");
        return "redirect:/settings";
    }

    @PostMapping("/api-key")
    public String generateApiKey(final Authentication authentication, final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final String apiKey = ENCODER.encodeToString(bytes);
        user.setApiKey(apiKeyHashingService.hash(apiKey));
        userRepository.save(user);
        auditLogService.log("API_KEY_GENERATE", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("apiKey", apiKey);
        return "redirect:/settings";
    }

    @PostMapping("/api-key/revoke")
    public String revokeApiKey(final Authentication authentication, final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
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
    public String changePassword(@RequestParam("currentPassword") final String currentPassword,
                                 @RequestParam("newPassword") final String newPassword,
                                 @RequestParam("confirmPassword") final String confirmPassword,
                                 final Authentication authentication,
                                 final RedirectAttributes redirectAttributes) {
        if (newPassword == null || newPassword.length() < 12) {
            redirectAttributes.addFlashAttribute("error", "New password must be at least 12 characters.");
            return "redirect:/settings";
        }
        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New password and confirmation do not match.");
            return "redirect:/settings";
        }

        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
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
