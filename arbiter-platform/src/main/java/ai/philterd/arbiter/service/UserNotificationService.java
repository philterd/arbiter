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
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.NotificationSettings;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.service.NotificationSettingsService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Service
public class UserNotificationService {

    private static final Logger log = LoggerFactory.getLogger(UserNotificationService.class);

    private final NotificationSettingsService settingsService;
    private final GeneralSettingsService generalSettingsService;

    public UserNotificationService(final NotificationSettingsService settingsService,
                                   final GeneralSettingsService generalSettingsService) {
        this.settingsService = settingsService;
        this.generalSettingsService = generalSettingsService;
    }

    /** Try to send a small test email using the supplied settings. Throws on failure. */
    public void sendTestEmail(final NotificationSettings probe, final String toEmail) throws Exception {
        if (probe.getHost() == null || probe.getHost().isBlank()) {
            throw new IllegalArgumentException("SMTP host is required.");
        }
        if (probe.getFromAddress() == null || probe.getFromAddress().isBlank()) {
            throw new IllegalArgumentException("From address is required.");
        }
        final JavaMailSenderImpl mailSender = buildSender(probe);
        final MimeMessage message = mailSender.createMimeMessage();
        final MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        if (probe.getFromName() != null && !probe.getFromName().isBlank()) {
            helper.setFrom(new InternetAddress(probe.getFromAddress(), probe.getFromName()));
        } else {
            helper.setFrom(probe.getFromAddress());
        }
        helper.setTo(toEmail);
        helper.setSubject("Arbiter SMTP test");
        helper.setText("This is a test message sent from Arbiter to verify the configured SMTP settings.\n");
        mailSender.send(message);
    }

    /**
     * Send a new user a one-shot invitation link. The link points at
     * {@code /invitations/{token}}; the recipient sets their own password there. The
     * plaintext password is never typed by the admin and never traverses SMTP — only
     * the token does, and even a leaked token is single-shot and time-limited.
     */
    public boolean sendInvitation(final String toEmail, final String invitationLink) {
        // R2-F10: email addresses are PII (account-identifying) per CLAUDE.md.
        // Log only an outcome-shaped message; an operator triaging from
        // application.log can correlate to the audit-log USER_INVITATION_ISSUED
        // row via timestamp when they need the email.
        final NotificationSettings settings = settingsService.load();
        if (!settings.isEnabled()) {
            log.warn("Outbound email is disabled; skipping invitation send.");
            return false;
        }
        if (settings.getHost() == null || settings.getHost().isBlank()) {
            log.warn("Outbound email is enabled but no SMTP host is configured; skipping invitation send.");
            return false;
        }
        if (settings.getFromAddress() == null || settings.getFromAddress().isBlank()) {
            log.warn("Outbound email is enabled but no from-address is configured; skipping invitation send.");
            return false;
        }

        final JavaMailSenderImpl mailSender = buildSender(settings);
        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (settings.getFromName() != null && !settings.getFromName().isBlank()) {
                helper.setFrom(new InternetAddress(settings.getFromAddress(), settings.getFromName()));
            } else {
                helper.setFrom(settings.getFromAddress());
            }
            helper.setTo(toEmail);
            helper.setSubject("Your Arbiter invitation");
            helper.setText(buildInvitationBody(toEmail, invitationLink));
            mailSender.send(message);
            log.info("Sent invitation email.");
            return true;
        } catch (Exception e) {
            // Log only the exception class + message — both can leak SMTP server
            // diagnostics referring to the recipient, but the recipient itself
            // doesn't appear in the parameter list any longer.
            log.warn("Failed to send invitation email: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private static JavaMailSenderImpl buildSender(final NotificationSettings settings) {
        final JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) {
            sender.setUsername(settings.getUsername());
        }
        if (settings.getPassword() != null && !settings.getPassword().isEmpty()) {
            sender.setPassword(settings.getPassword());
        }
        final Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) {
            props.put("mail.smtp.auth", "true");
        }
        if (settings.isUseStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        if (settings.isUseSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    /** Build a {@code /invitations/{token}} link rooted at the configured Arbiter URL. */
    public String buildInvitationLink(final String token) {
        String url = generalSettingsService.load().getArbiterUrl();
        if (url == null || url.isBlank()) return null;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url + "/invitations/" + token;
    }

    private static String buildInvitationBody(final String email, final String invitationLink) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("An Arbiter account has been created for ").append(email).append(".\n\n");
        sb.append("To finish setting up your account, follow this link and choose a password:\n\n");
        if (invitationLink != null && !invitationLink.isBlank()) {
            sb.append("    ").append(invitationLink).append('\n');
        } else {
            sb.append("    (the link to your invitation could not be built — ");
            sb.append("ask your administrator to configure the Arbiter URL under General settings)\n");
        }
        sb.append("\nThe link is single-use and expires in 7 days.\n");
        return sb.toString();
    }
}
