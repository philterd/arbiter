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

import ai.philterd.workspace.model.User;
import ai.philterd.workspace.model.UserSettings;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.UserSettingsService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSignInService;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.SignInThrottledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


/**
 * A person's own settings: their review preferences, their Philter account (username, email, and role), and
 * their password and MFA, which Philter keeps. Their API keys and webhook are on {@link PhilterAccountController}'s
 * tabs.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

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
    public String settings(final Authentication authentication, final HttpServletRequest request, final Model model) {
        model.addAttribute("userSettings", userSettingsService.loadForEmail(
                authentication == null ? null : authentication.getName()));
        if (authentication instanceof PhilterSessionAuthentication philter) {
            try {
                model.addAttribute("account", philterSignInService.currentUser(philter.getEncryptedSessionKey()));
            } catch (UnauthorizedException e) {
                return PhilterSessions.end(request);
            } catch (Exception e) {
                log.warn("Could not read the signed-in person's Philter account: {}", PhilterClients.describe(e));
                model.addAttribute("accountError", "Philter could not be reached, so your account details and "
                        + "multi-factor authentication cannot be shown.");
            }
        }
        model.addAttribute("activeTab", "account");
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
        // Philter's audit log records the change (user_password_changed), so it is not repeated here.
        final HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?passwordChanged";
    }
}
