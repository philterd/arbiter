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

    public UserNotificationService(NotificationSettingsService settingsService,
                                   GeneralSettingsService generalSettingsService) {
        this.settingsService = settingsService;
        this.generalSettingsService = generalSettingsService;
    }

    /** Try to send a small test email using the supplied settings. Throws on failure. */
    public void sendTestEmail(NotificationSettings probe, String toEmail) throws Exception {
        if (probe.getHost() == null || probe.getHost().isBlank()) {
            throw new IllegalArgumentException("SMTP host is required.");
        }
        if (probe.getFromAddress() == null || probe.getFromAddress().isBlank()) {
            throw new IllegalArgumentException("From address is required.");
        }
        JavaMailSenderImpl mailSender = buildSender(probe);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
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

    /** Send a new user their login credentials. Returns true if the email was sent. */
    public boolean sendNewUserCredentials(String toEmail, String password) {
        String loginUrl = buildLoginUrl();
        NotificationSettings settings = settingsService.load();
        if (!settings.isEnabled()) {
            log.warn("Outbound email is disabled; skipping new-user welcome to {}", toEmail);
            return false;
        }
        if (settings.getHost() == null || settings.getHost().isBlank()) {
            log.warn("Outbound email is enabled but no SMTP host is configured; skipping welcome to {}", toEmail);
            return false;
        }
        if (settings.getFromAddress() == null || settings.getFromAddress().isBlank()) {
            log.warn("Outbound email is enabled but no from-address is configured; skipping welcome to {}", toEmail);
            return false;
        }

        JavaMailSenderImpl mailSender = buildSender(settings);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (settings.getFromName() != null && !settings.getFromName().isBlank()) {
                helper.setFrom(new InternetAddress(settings.getFromAddress(), settings.getFromName()));
            } else {
                helper.setFrom(settings.getFromAddress());
            }
            helper.setTo(toEmail);
            helper.setSubject("Your Arbiter account");
            helper.setText(buildBody(toEmail, password, loginUrl));
            mailSender.send(message);
            log.info("Sent new-user welcome email to {}", toEmail);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send new-user welcome email to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    private static JavaMailSenderImpl buildSender(NotificationSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) {
            sender.setUsername(settings.getUsername());
        }
        if (settings.getPassword() != null && !settings.getPassword().isEmpty()) {
            sender.setPassword(settings.getPassword());
        }
        Properties props = sender.getJavaMailProperties();
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

    private String buildLoginUrl() {
        String url = generalSettingsService.load().getArbiterUrl();
        if (url == null || url.isBlank()) return null;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url + "/login";
    }

    private static String buildBody(String email, String password, String loginUrl) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("An Arbiter account has been created for you.\n\n");
        sb.append("Email:    ").append(email).append('\n');
        sb.append("Password: ").append(password).append('\n');
        if (loginUrl != null && !loginUrl.isBlank()) {
            sb.append("Sign in:  ").append(loginUrl).append('\n');
        }
        sb.append("\nPlease change your password after signing in.\n");
        return sb.toString();
    }
}
