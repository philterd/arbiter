/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.webapp.security.PhilterWebhookReceiver;
import ai.philterd.workspace.webapp.security.PhilterWebhookSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * {@code POST /api/philter/webhook}: Philter's signed webhook deliveries. Unauthenticated, since Philter signs
 * the request instead: a delivery is handled only when its HMAC signature matches the shared secret and its
 * timestamp is current. Answers 404 when no secret is set, 401 when the signature or timestamp is refused,
 * and 204 when the delivery is accepted, including a delivery received before.
 */
@RestController
public class PhilterWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PhilterWebhookController.class);

    public static final String PATH = "/api/philter/webhook";

    /** Philter's deliveries are a few hundred bytes; anything larger is refused unread. */
    static final int MAXIMUM_BODY_BYTES = 64 * 1024;

    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern EVENT = Pattern.compile("[A-Z0-9_]{1,64}");

    private final PhilterWebhookSettings settings;
    private final PhilterWebhookReceiver receiver;

    public PhilterWebhookController(final PhilterWebhookSettings settings, final PhilterWebhookReceiver receiver) {
        this.settings = settings;
        this.receiver = receiver;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Philter-Event", required = false) final String event,
                                        @RequestHeader(value = "X-Philter-Delivery-Id", required = false) final String deliveryId,
                                        @RequestHeader(value = "X-Philter-Timestamp", required = false) final String timestamp,
                                        @RequestHeader(value = "X-Philter-Signature", required = false) final String signature,
                                        final HttpServletRequest request) throws IOException {
        if (!settings.enabled()) {
            return ResponseEntity.notFound().build();
        }
        // Checked before anything is logged, so a caller cannot write arbitrary text into the log.
        if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()
                || event == null || !EVENT.matcher(event).matches()) {
            log.warn("Refused a Philter webhook delivery with a missing or malformed delivery id or event");
            return ResponseEntity.badRequest().build();
        }
        final byte[] body = readBody(request);
        if (body == null) {
            log.warn("Refused Philter webhook delivery {}: the body is over {} bytes", deliveryId, MAXIMUM_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        final PhilterWebhookSettings.Verification verification =
                settings.verify(timestamp, signature, body, Instant.now());
        if (verification != PhilterWebhookSettings.Verification.VALID) {
            log.warn("Refused Philter webhook delivery {} ({}): {}", deliveryId, event, verification.reason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        receiver.receive(deliveryId, event);
        return ResponseEntity.noContent().build();
    }

    /** The body, or null when it is over {@link #MAXIMUM_BODY_BYTES}. */
    private static byte[] readBody(final HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAXIMUM_BODY_BYTES) {
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            final byte[] body = in.readNBytes(MAXIMUM_BODY_BYTES + 1);
            return body.length > MAXIMUM_BODY_BYTES ? null : body;
        }
    }
}
