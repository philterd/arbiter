/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A webhook delivery received from Philter, keyed by its {@code X-Philter-Delivery-Id}. Philter delivers
 * at least once and keeps a delivery's id across retries, so a delivery already recorded here is
 * acknowledged without being handled again. Records are pruned once Philter can no longer retry them.
 */
@Document(collection = "philter_webhook_deliveries")
public class PhilterWebhookDelivery {

    @Id
    private String id;
    private String event;
    @Indexed
    private Instant receivedAt;

    public PhilterWebhookDelivery() {
    }

    public PhilterWebhookDelivery(final String id, final String event, final Instant receivedAt) {
        this.id = id;
        this.event = event;
        this.receivedAt = receivedAt;
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getEvent() { return event; }
    public void setEvent(final String event) { this.event = event; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(final Instant receivedAt) { this.receivedAt = receivedAt; }
}
