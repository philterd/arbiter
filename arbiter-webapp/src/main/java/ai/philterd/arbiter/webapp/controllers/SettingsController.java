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
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.arbiter.service.UserSettingsService;
import ai.philterd.arbiter.webapp.security.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
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
import java.util.OptionalLong;

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
    private final SymmetricCipher cipher;
    private final SessionRegistry sessionRegistry;

    public SettingsController(final UserRepository userRepository,
                              final PasswordEncoder passwordEncoder,
                              final AuditLogService auditLogService,
                              final UserSettingsService userSettingsService,
                              final TotpService totpService,
                              final ApiKeyHashingService apiKeyHashingService,
                              final SymmetricCipher cipher,
                              final SessionRegistry sessionRegistry) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.userSettingsService = userSettingsService;
        this.totpService = totpService;
        this.apiKeyHashingService = apiKeyHashingService;
        this.cipher = cipher;
        this.sessionRegistry = sessionRegistry;
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
                           final Model model,
                           final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) return "redirect:/settings";
        // A session-hijacker on an already-MFA-enabled account could otherwise re-enroll
        // their own secret here, silently rebinding the second factor to a device they
        // control. Force a Disable → Re-enable round trip (Disable already requires the
        // current password and a TOTP code) so taking over the second factor demands the
        // attacker know the password, not just hold the session cookie.
        if (user.isMfaEnabled()) {
            redirectAttributes.addFlashAttribute("error",
                    "MFA is already enabled. Disable it first if you want to re-enroll a new device.");
            return "redirect:/settings";
        }

        final String secret = totpService.generateSecret();
        session.setAttribute(TOTP_SETUP_SECRET, secret);
        model.addAttribute("qrCodeDataUri", totpService.qrCodeDataUri(authentication.getName(), secret));
        model.addAttribute("secret", secret);
        return "mfa-setup";
    }

    @PostMapping("/mfa/enable")
    public String mfaEnable(@RequestParam("currentPassword") final String currentPassword,
                            @RequestParam("code") final String code,
                            @RequestParam(value = "required", defaultValue = "false") final boolean required,
                            final Authentication authentication,
                            final HttpSession session,
                            final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        // Defence-in-depth against the same takeover the setup-page guard prevents — if
        // the attacker raced a setup that started before MFA was enabled, refuse to
        // overwrite the existing secret without going through Disable first.
        if (user.isMfaEnabled()) {
            redirectAttributes.addFlashAttribute("error",
                    "MFA is already enabled. Disable it first if you want to re-enroll a new device.");
            return "redirect:/settings";
        }
        // Re-authenticate the holder of the session before we trust them with a new MFA
        // device. Without this, anyone who steals a session cookie can rebind the second
        // factor to their authenticator and lock the legitimate user out — inverting the
        // MFA threat model. The setup secret in session stays put on a wrong password so
        // the user can retry without rescanning the QR.
        final String redirectOnFailure = required
                ? "redirect:/settings/mfa/setup?required=true"
                : "redirect:/settings/mfa/setup";
        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error",
                    "Current password is incorrect. MFA was not enabled.");
            return redirectOnFailure;
        }
        final String secret = (String) session.getAttribute(TOTP_SETUP_SECRET);
        if (secret == null) {
            redirectAttributes.addFlashAttribute("error", "Setup session expired. Please try again.");
            return redirectOnFailure;
        }
        final OptionalLong enrollStep = totpService.verifyAndReturnStep(secret, code);
        if (enrollStep.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Invalid code. Please scan the QR code again and try once more.");
            return redirectOnFailure;
        }
        // Encrypt the TOTP shared secret at rest. A DB compromise (leaked backup,
        // admin-readable export, misconfigured Mongo) would otherwise hand the
        // attacker enough to compute the victim's current TOTP and bypass their
        // second factor permanently. encryptField is idempotent against the
        // FIELD_PREFIX marker so a re-save of a loaded user does not double-encrypt.
        user.setTotpSecret(cipher.encryptField(secret));
        user.setMfaEnabled(true);
        // Seed the replay-protection step with the enrol code so that same code
        // can't immediately be replayed on /mfa (R2-F11).
        user.setLastTotpStep(enrollStep.getAsLong());
        userRepository.save(user);
        session.removeAttribute(TOTP_SETUP_SECRET);
        auditLogService.log("MFA_ENABLED", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "Two-factor authentication enabled.");
        return "redirect:/settings";
    }

    @PostMapping("/mfa/disable")
    public String mfaDisable(@RequestParam("currentPassword") final String currentPassword,
                             @RequestParam("code") final String code,
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
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect. MFA was not disabled.");
            return "redirect:/settings";
        }
        // Decrypt the stored secret before verifying. decryptField passes legacy
        // plaintext rows through unchanged so accounts whose MFA was set up before
        // this change keep working until the user next disables/re-enables.
        final OptionalLong disableStep =
                totpService.verifyAndReturnStep(cipher.decryptField(user.getTotpSecret()), code);
        final boolean disableReplayed = disableStep.isPresent()
                && user.getLastTotpStep() != null
                && disableStep.getAsLong() <= user.getLastTotpStep();
        if (disableStep.isEmpty() || disableReplayed) {
            redirectAttributes.addFlashAttribute("error", "Invalid code. MFA was not disabled.");
            return "redirect:/settings";
        }
        user.setTotpSecret(null);
        user.setMfaEnabled(false);
        // Even though we're disabling, persist the accepted step. Re-enrolment
        // generates a new secret so lastTotpStep gets re-seeded; meanwhile, if
        // someone else captures this disable code they can't replay it.
        user.setLastTotpStep(disableStep.getAsLong());
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
    public String generateApiKey(@RequestParam("currentPassword") final String currentPassword,
                                 final Authentication authentication,
                                 final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect. API key was not generated.");
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
    public String revokeApiKey(@RequestParam("currentPassword") final String currentPassword,
                               final Authentication authentication,
                               final RedirectAttributes redirectAttributes) {
        final User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Account not found.");
            return "redirect:/settings";
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirectAttributes.addFlashAttribute("error", "Current password is incorrect. API key was not revoked.");
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
                                 final HttpServletRequest request,
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
        // Clear any "must change password" flag set by the admin who created or
        // reset this account — the user has now rotated the password the admin
        // gave them, so the gate that forces them onto this page is satisfied.
        user.setMustChangePassword(false);
        try {
            userRepository.save(user);
        } catch (OptimisticLockingFailureException conflict) {
            // R2-F13: a concurrent write to this user row beat us. The other
            // write may have been the user racing themselves from another tab,
            // or an admin's reset landing simultaneously. Surface a benign
            // retry message rather than swallowing the conflict (last-write-
            // wins on a credential change is the correctness defect we just
            // added @Version to detect).
            redirectAttributes.addFlashAttribute("error",
                    "Your account was updated by another session while you were changing your "
                            + "password. Please sign in again and retry.");
            return "redirect:/settings";
        }

        // R2-F12: a hijacked session must not survive the credential rotation.
        // 1) Rotate THIS session id so any pre-rotation cookie is dead even on
        //    this browser. (Spring's sessionFixation policy also does this on
        //    re-auth, but we do it explicitly here because the password change
        //    is not a fresh authentication.)
        // 2) Expire any OTHER live sessions for the same principal so an
        //    attacker who still has the old cookie is kicked out.
        final HttpSession current = request.getSession(false);
        final String currentId = current == null ? null : current.getId();
        request.changeSessionId();
        expireOtherSessions(user.getEmail(), currentId);

        auditLogService.log("PASSWORD_CHANGE", "User", user.getId(), null);
        redirectAttributes.addFlashAttribute("success", "Password updated.");
        return "redirect:/settings";
    }

    private void expireOtherSessions(final String principal, final String currentSessionId) {
        if (principal == null) return;
        // SessionRegistry.getAllSessions(principal, false) excludes already-expired
        // sessions; expire any whose id is NOT the caller's current session.
        sessionRegistry.getAllSessions(principal, false).forEach(info -> {
            if (currentSessionId == null || !currentSessionId.equals(info.getSessionId())) {
                info.expireNow();
            }
        });
    }
}
