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

import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The webhook Philter calls when a job queued with Philter Workspace's API key finishes: the shared secret it
 * signs each delivery with ({@code PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET}) and the URL it calls
 * ({@code PHILTER_WORKSPACE_PHILTER_WEBHOOK_URL}). Without a secret, Philter Workspace receives no webhooks. A bad
 * value stops Philter Workspace at startup. The secret never leaves this class except to Philter, when the
 * webhook is registered.
 */
@Component
public class PhilterWebhookSettings {

    /** Philter's own minimum for a webhook secret. */
    static final int MINIMUM_SECRET_LENGTH = 16;

    /** How far a delivery's timestamp may be from now, as Philter's docs require of receivers. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static final String SIGNATURE_PREFIX = "sha256=";

    /** Why a delivery was accepted or refused. */
    public enum Verification {
        VALID("valid"),
        MISSING("no timestamp or signature"),
        MALFORMED("a malformed timestamp or signature"),
        INVALID_SIGNATURE("a signature that does not match"),
        STALE("a timestamp more than 5 minutes from now");

        private final String reason;

        Verification(final String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private final String secret;
    private final String url;

    public PhilterWebhookSettings(@Value("${philter-workspace.philter.webhook-secret:}") final String secret,
                                  @Value("${philter-workspace.philter.webhook-url:}") final String url) {
        this.secret = secret == null || secret.isBlank() ? null : secret.trim();
        if (this.secret != null && this.secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException("PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET must be at least "
                    + MINIMUM_SECRET_LENGTH + " characters, as Philter requires. Generate one with: openssl rand -hex 24");
        }
        this.url = url == null || url.isBlank() ? null : validUrl(url.trim());
    }

    /** Whether Philter Workspace receives webhooks: a secret is set. */
    public boolean enabled() {
        return secret != null;
    }

    /** The URL Philter is to call, or null when not set. */
    public String url() {
        return url;
    }

    /** Whether the webhook can be registered with Philter: both the secret and the URL are set. */
    public boolean registrable() {
        return secret != null && url != null;
    }

    /** Register the URL and secret as the webhook of {@code client}'s user. */
    public Webhook register(final PhilterClient client) throws IOException {
        if (!registrable()) {
            throw new IllegalStateException("The webhook URL and secret are not both set.");
        }
        return client.setWebhook(url, secret);
    }

    /**
     * Check a delivery as Philter's docs require: an HMAC-SHA256 of {@code "<timestamp>.<body>"} with the
     * secret, compared in constant time, and a timestamp within {@link #TOLERANCE} of {@code now}.
     */
    public Verification verify(final String timestamp, final String signature, final byte[] body, final Instant now) {
        if (secret == null || timestamp == null || signature == null || body == null) {
            return Verification.MISSING;
        }
        final long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Verification.MALFORMED;
        }
        final String trimmed = signature.trim();
        if (!trimmed.startsWith(SIGNATURE_PREFIX)) {
            return Verification.MALFORMED;
        }
        final byte[] provided = trimmed.substring(SIGNATURE_PREFIX.length()).toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(provided, sign(timestamp.trim(), body).getBytes(StandardCharsets.US_ASCII))) {
            return Verification.INVALID_SIGNATURE;
        }
        if (Math.abs(now.getEpochSecond() - seconds) > TOLERANCE.toSeconds()) {
            return Verification.STALE;
        }
        return Verification.VALID;
    }

    /** The {@code X-Philter-Signature} value, without its prefix, for {@code timestamp} and {@code body}. */
    String sign(final String timestamp, final byte[] body) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static String validUrl(final String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PHILTER_WORKSPACE_PHILTER_WEBHOOK_URL is not a valid URL.");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalStateException("PHILTER_WORKSPACE_PHILTER_WEBHOOK_URL must be an http or https URL, "
                    + "such as https://workspace.example.com/api/philter/webhook.");
        }
        return value;
    }
}
