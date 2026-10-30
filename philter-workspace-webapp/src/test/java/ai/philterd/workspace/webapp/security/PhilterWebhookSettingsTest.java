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

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import static ai.philterd.workspace.webapp.security.PhilterWebhookSettings.Verification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifying Philter's webhook signatures as Philter's docs describe them (#14). */
class PhilterWebhookSettingsTest {

    static final String SECRET = "a-shared-secret-of-at-least-16-characters";
    static final byte[] BODY = "{\"event\":\"WEBHOOK_TEST\",\"test\":true,\"timestamp\":\"2026-10-30T12:00:00Z\"}"
            .getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final PhilterWebhookSettings settings = new PhilterWebhookSettings(SECRET, "https://workspace.example.com/api/philter/webhook");

    /** Philter's scheme, written out independently: HMAC-SHA256 of "<timestamp>.<body>", as sha256=<hex>. */
    static String philterSignature(final String secret, final long timestamp, final byte[] body) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final String signed = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aDeliveryPhilterSignedIsValid() throws Exception {
        final long ts = NOW.getEpochSecond();
        assertEquals(Verification.VALID, settings.verify(Long.toString(ts), philterSignature(SECRET, ts, BODY), BODY, NOW));
        // Upper-case hex is the same signature.
        assertEquals(Verification.VALID, settings.verify(Long.toString(ts),
                philterSignature(SECRET, ts, BODY).toUpperCase(Locale.ROOT).replace("SHA256=", "sha256="), BODY, NOW));
    }

    @Test
    void anotherSecretOrAChangedBodyOrTimestampIsRefused() throws Exception {
        final long ts = NOW.getEpochSecond();
        assertEquals(Verification.INVALID_SIGNATURE,
                settings.verify(Long.toString(ts), philterSignature("another-secret-of-16-chars", ts, BODY), BODY, NOW));
        final byte[] changed = "{\"event\":\"DOCUMENT_REDACTION_FAILED\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Verification.INVALID_SIGNATURE,
                settings.verify(Long.toString(ts), philterSignature(SECRET, ts, BODY), changed, NOW));
        // The timestamp is signed, so moving it forward to dodge the time check breaks the signature.
        assertEquals(Verification.INVALID_SIGNATURE,
                settings.verify(Long.toString(ts + 1), philterSignature(SECRET, ts, BODY), BODY, NOW));
    }

    @Test
    void aDeliveryMoreThanFiveMinutesFromNowIsRefused() throws Exception {
        final long old = NOW.getEpochSecond() - 301;
        assertEquals(Verification.STALE, settings.verify(Long.toString(old), philterSignature(SECRET, old, BODY), BODY, NOW));
        final long future = NOW.getEpochSecond() + 301;
        assertEquals(Verification.STALE,
                settings.verify(Long.toString(future), philterSignature(SECRET, future, BODY), BODY, NOW));
        final long edge = NOW.getEpochSecond() - 300;
        assertEquals(Verification.VALID, settings.verify(Long.toString(edge), philterSignature(SECRET, edge, BODY), BODY, NOW));
    }

    @Test
    void missingOrMalformedHeadersAreRefused() throws Exception {
        final long ts = NOW.getEpochSecond();
        final String signature = philterSignature(SECRET, ts, BODY);
        assertEquals(Verification.MISSING, settings.verify(null, signature, BODY, NOW));
        assertEquals(Verification.MISSING, settings.verify(Long.toString(ts), null, BODY, NOW));
        assertEquals(Verification.MALFORMED, settings.verify("yesterday", signature, BODY, NOW));
        assertEquals(Verification.MALFORMED, settings.verify(Long.toString(ts), signature.substring(7), BODY, NOW));
        assertEquals(Verification.MISSING, new PhilterWebhookSettings("", "").verify(Long.toString(ts), signature, BODY, NOW));
    }

    @Test
    void settingsAreCheckedAtStartup() {
        assertFalse(new PhilterWebhookSettings("", "").enabled());
        assertFalse(new PhilterWebhookSettings(SECRET, "").registrable());
        assertTrue(settings.registrable());
        assertTrue(assertThrows(IllegalStateException.class, () -> new PhilterWebhookSettings("too-short", ""))
                .getMessage().startsWith("PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET must be at least 16 characters"));
        assertThrows(IllegalStateException.class, () -> new PhilterWebhookSettings(SECRET, "ftp://workspace.example.com/x"));
        assertThrows(IllegalStateException.class, () -> new PhilterWebhookSettings(SECRET, "not a url"));
    }
}
