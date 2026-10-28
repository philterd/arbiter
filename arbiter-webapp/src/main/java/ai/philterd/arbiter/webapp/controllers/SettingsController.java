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
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.arbiter.webapp.security.PhilterSignInService;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.SignInThrottledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * A person's own settings: their review preferences, and their password, which is kept by Philter. MFA
 * and API keys are managed in Philter.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final UserSettingsService userSettingsService;
    private final PhilterSignInService philterSignInService;

    public SettingsController(final UserRepository userRepository,
                              final AuditLogService auditLogService,
                              final UserSettingsService userSettingsService,
                              final PhilterSignInService philterSignInService) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.userSettingsService = userSettingsService;
        this.philterSignInService = philterSignInService;
    }

    @GetMapping
    public String settings(final Authentication authentication, final Model model) {
        model.addAttribute("userSettings", userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName()));
        return "settings";
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

    /**
     * Changes the person's password in Philter. Philter then revokes all of their session keys, so this
     * session ends too and they sign in again with the new password.
     */
    @PostMapping("/password")
    public String changePassword(@RequestParam("currentPassword") final String currentPassword,
                                 @RequestParam("newPassword") final String newPassword,
                                 @RequestParam("confirmPassword") final String confirmPassword,
                                 final Authentication authentication,
                                 final HttpServletRequest request,
                                 final RedirectAttributes redirectAttributes) {
        if (!(authentication instanceof PhilterSessionAuthentication philter)) {
            redirectAttributes.addFlashAttribute("error", "Sign in again to change your password.");
            return "redirect:/settings";
        }
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "New password and confirmation do not match.");
            return "redirect:/settings";
        }
        try {
            philterSignInService.changePassword(philter.getEncryptedSessionKey(),
                    currentPassword, newPassword);
        } catch (SignInThrottledException e) {
            redirectAttributes.addFlashAttribute("error",
                    "Too many attempts with a wrong password. Try again later.");
            return "redirect:/settings";
        } catch (ClientException e) {
            redirectAttributes.addFlashAttribute("error", e.getStatusCode() == 400
                    ? "Philter did not accept the new password. It must be at least 16 characters and "
                            + "differ from the current one."
                    : "The password was not changed. Check the current password.");
            return "redirect:/settings";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "The password was not changed. Check the current "
                    + "password, or sign in again.");
            return "redirect:/settings";
        }
        final User user = userRepository.findByEmail(philter.getName()).orElse(null);
        auditLogService.log("PASSWORD_CHANGE", "User", user == null ? null : user.getId(),
                Map.of("method", "philter"));
        final HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?passwordChanged";
    }
}
