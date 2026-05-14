/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.NotificationSettings;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests R2-F10: the recipient email address must never appear in
 * {@link UserNotificationService}'s application log lines. CLAUDE.md classifies
 * email as PII; operators with log-read access (a common separation-of-duties
 * role) shouldn't get a complete roster of every onboarded user.
 */
class UserNotificationServiceLogTest {

    private static final String RECIPIENT = "jane.doe@example.com";

    private NotificationSettingsService settingsService;
    private GeneralSettingsService generalSettingsService;
    private UserNotificationService svc;
    private ListAppender<ILoggingEvent> appender;
    private Logger captureLogger;

    @BeforeEach
    void setUp() {
        settingsService = mock(NotificationSettingsService.class);
        generalSettingsService = mock(GeneralSettingsService.class);
        svc = new UserNotificationService(settingsService, generalSettingsService);

        captureLogger = (Logger) LoggerFactory.getLogger(UserNotificationService.class);
        appender = new ListAppender<>();
        appender.start();
        captureLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        captureLogger.detachAppender(appender);
        appender.stop();
    }

    private static NotificationSettings disabled() {
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(false);
        return s;
    }

    private static NotificationSettings missingHost() {
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(true);
        s.setHost("");
        s.setFromAddress("from@arbiter.local");
        return s;
    }

    private static NotificationSettings missingFromAddress() {
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(true);
        s.setHost("smtp.example.com");
        s.setPort(25);
        s.setFromAddress(null);
        return s;
    }

    private static NotificationSettings reachableButUnconnectable() {
        // Will be picked up by buildSender but the actual send fails because
        // there's nothing listening on the configured port. Drives the catch
        // path so we can assert on the failure log.
        final NotificationSettings s = new NotificationSettings();
        s.setEnabled(true);
        s.setHost("127.0.0.1");
        s.setPort(1);     // reserved port — connect will refuse
        s.setFromAddress("from@arbiter.local");
        return s;
    }

    private void assertNoRecipientInLogs() {
        for (ILoggingEvent e : appender.list) {
            final String rendered = e.getFormattedMessage();
            assertFalse(rendered.contains(RECIPIENT),
                    "log line leaks recipient email: " + rendered);
            // Even individual segments — the local part and the domain — must not appear.
            assertFalse(rendered.contains("jane.doe"),
                    "log line leaks email local-part: " + rendered);
        }
    }

    @Test
    void disabledEmailWarningDoesNotLogRecipient() {
        when(settingsService.load()).thenReturn(disabled());

        final boolean sent = svc.sendInvitation(RECIPIENT, "https://arbiter/invitations/tok");

        assertFalse(sent);
        assertTrue(appender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().toLowerCase().contains("outbound email is disabled")),
                "expected the 'disabled' warning to be present");
        assertNoRecipientInLogs();
    }

    @Test
    void missingHostWarningDoesNotLogRecipient() {
        when(settingsService.load()).thenReturn(missingHost());

        svc.sendInvitation(RECIPIENT, "https://arbiter/invitations/tok");

        assertNoRecipientInLogs();
    }

    @Test
    void missingFromAddressWarningDoesNotLogRecipient() {
        when(settingsService.load()).thenReturn(missingFromAddress());

        svc.sendInvitation(RECIPIENT, "https://arbiter/invitations/tok");

        assertNoRecipientInLogs();
    }

    @Test
    void smtpFailureWarningDoesNotLogRecipient() {
        when(settingsService.load()).thenReturn(reachableButUnconnectable());

        final boolean sent = svc.sendInvitation(RECIPIENT, "https://arbiter/invitations/tok");

        assertFalse(sent, "send must fail when SMTP is unreachable");
        // A failure log line should exist, but with the exception class/message,
        // NOT the recipient email.
        assertTrue(appender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().toLowerCase().contains("failed to send invitation email")),
                "expected the failure warning to be emitted");
        assertNoRecipientInLogs();
    }
}
