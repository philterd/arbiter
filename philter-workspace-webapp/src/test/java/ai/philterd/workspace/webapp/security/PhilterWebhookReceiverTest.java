/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import ai.philterd.workspace.model.PhilterWebhookDelivery;
import ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterWebhookReceiverTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final PhilterWebhookDeliveryRepository deliveries = mock(PhilterWebhookDeliveryRepository.class);
    private final PhilterWebhookReceiver receiver = new PhilterWebhookReceiver(deliveries, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aDeliveryIsRecordedOnceAndARetryIsOnlyAcknowledged() {
        assertEquals(PhilterWebhookReceiver.Outcome.HANDLED, receiver.receive("6a1106e9f5b4e90cb1d35a01", "WEBHOOK_TEST"));
        final ArgumentCaptor<PhilterWebhookDelivery> saved = ArgumentCaptor.forClass(PhilterWebhookDelivery.class);
        verify(deliveries).save(saved.capture());
        assertEquals("6a1106e9f5b4e90cb1d35a01", saved.getValue().getId());
        assertEquals("WEBHOOK_TEST", saved.getValue().getEvent());
        assertEquals(NOW, saved.getValue().getReceivedAt());

        when(deliveries.existsById("6a1106e9f5b4e90cb1d35a02")).thenReturn(true);
        assertEquals(PhilterWebhookReceiver.Outcome.DUPLICATE, receiver.receive("6a1106e9f5b4e90cb1d35a02", "WEBHOOK_TEST"));
        verify(deliveries, never()).save(org.mockito.ArgumentMatchers.argThat(d -> "6a1106e9f5b4e90cb1d35a02".equals(d.getId())));
    }

    @Test
    void eventsPhilterWorkspaceDoesNotHandleAreStillAcknowledged() {
        assertEquals(PhilterWebhookReceiver.Outcome.HANDLED, receiver.receive("a1", "DOCUMENT_REDACTION_COMPLETE"));
        assertEquals(PhilterWebhookReceiver.Outcome.HANDLED, receiver.receive("a2", "SPAN_PROPOSAL_COMPLETE"));
    }

    @Test
    void recordsArePrunedAfterSevenDays() {
        receiver.prune();
        verify(deliveries).deleteByReceivedAtBefore(Instant.parse("2026-10-23T12:00:00Z"));
        verify(deliveries, never()).save(any());
    }
}
