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

import java.util.Base64;

/**
 * Single source of truth for validating {@code arbiter.crypto.secret}. Both
 * {@link ApiKeyHashingService} and {@link SymmetricCipher} consume the same property and
 * apply the same constraints, so the validation lives here and they each delegate.
 *
 * <p>On failure throws {@link CryptoSecretConfigurationException} carrying a
 * {@link CryptoSecretConfigurationException.Reason} the {@link CryptoSecretFailureAnalyzer}
 * uses to render a clean banner instead of a stack trace.
 */
final class CryptoSecretLoader {

    /** Required key size in bytes (AES-256 / HMAC-SHA-256 with full-strength key). */
    static final int REQUIRED_BYTES = 32;

    /** Literal value shipped in {@code .env.example}. Detected explicitly so an operator
     *  who copies the example without editing it gets a targeted error. */
    static final String PLACEHOLDER = "replace-me-with-base64-of-32-random-bytes";

    private CryptoSecretLoader() {
    }

    /**
     * Decode and validate the configured secret. Returns the 32-byte key bytes on success
     * or throws {@link CryptoSecretConfigurationException} with a {@code Reason} the
     * failure analyzer can dispatch on.
     */
    static byte[] load(final String configured) {
        final String trimmed = configured == null ? "" : configured.trim();
        if (trimmed.isEmpty()) {
            throw new CryptoSecretConfigurationException(
                    CryptoSecretConfigurationException.Reason.UNSET,
                    "arbiter.crypto.secret is not set.");
        }
        if (PLACEHOLDER.equals(trimmed)) {
            throw new CryptoSecretConfigurationException(
                    CryptoSecretConfigurationException.Reason.PLACEHOLDER,
                    "arbiter.crypto.secret is still set to the placeholder value from .env.example.");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new CryptoSecretConfigurationException(
                    CryptoSecretConfigurationException.Reason.NOT_BASE64,
                    "arbiter.crypto.secret is not valid base64.");
        }
        if (decoded.length != REQUIRED_BYTES) {
            throw new CryptoSecretConfigurationException(
                    CryptoSecretConfigurationException.Reason.WRONG_LENGTH,
                    decoded.length,
                    "arbiter.crypto.secret decoded to " + decoded.length
                            + " bytes; expected exactly " + REQUIRED_BYTES + ".");
        }
        return decoded;
    }
}
