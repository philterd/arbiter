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
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.NotificationSettings;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Service
public class UserNotificationService {


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
        helper.setSubject("Philter Workspace SMTP test");
        helper.setText("This is a test message sent from Philter Workspace to verify the configured SMTP settings.\n");
        mailSender.send(message);
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
}
