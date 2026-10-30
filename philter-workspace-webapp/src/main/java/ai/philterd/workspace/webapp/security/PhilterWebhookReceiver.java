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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Handles a verified webhook delivery from Philter, once per {@code X-Philter-Delivery-Id}. Philter delivers
 * at least once, so a delivery already recorded is acknowledged and not handled again.
 *
 * <p>Philter Workspace queues no jobs in Philter yet, so no event changes a document: the test event and
 * Philter's redaction events are acknowledged, and so is any event Philter adds later, as Philter's docs ask.
 * Span-proposal results (philterd/philter-workspace#58) will be handled here, and must be idempotent too.
 */
@Service
public class PhilterWebhookReceiver {

    private static final Logger log = LoggerFactory.getLogger(PhilterWebhookReceiver.class);

    /** Longer than Philter keeps retrying a delivery (about four hours), so a retry is still recognized. */
    static final Duration KEEP = Duration.ofDays(7);

    public enum Outcome { HANDLED, DUPLICATE }

    private final PhilterWebhookDeliveryRepository deliveries;
    private final Clock clock;

    @Autowired
    public PhilterWebhookReceiver(final PhilterWebhookDeliveryRepository deliveries) {
        this(deliveries, Clock.systemUTC());
    }

    PhilterWebhookReceiver(final PhilterWebhookDeliveryRepository deliveries, final Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public Outcome receive(final String deliveryId, final String event) {
        if (deliveries.existsById(deliveryId)) {
            log.info("Philter webhook delivery {} ({}) was already received; acknowledged again", deliveryId, event);
            return Outcome.DUPLICATE;
        }
        switch (event) {
            case "WEBHOOK_TEST" -> log.info("Philter webhook test {} received", deliveryId);
            case "DOCUMENT_REDACTION_COMPLETE", "DOCUMENT_REDACTION_FAILED" ->
                    log.info("Philter webhook {} ({}) acknowledged: Philter Workspace queues no redaction jobs",
                            deliveryId, event);
            default -> log.info("Philter webhook {} has an event Philter Workspace does not handle ({}); acknowledged",
                    deliveryId, event);
        }
        deliveries.save(new PhilterWebhookDelivery(deliveryId, event, clock.instant()));
        return Outcome.HANDLED;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void prune() {
        final long removed = deliveries.deleteByReceivedAtBefore(clock.instant().minus(KEEP));
        if (removed > 0) {
            log.debug("Removed {} Philter webhook delivery record(s) older than {}", removed, KEEP);
        }
    }
}
