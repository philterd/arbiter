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

/**
 * Thrown when {@code arbiter.crypto.secret} is missing, malformed, or otherwise unusable.
 * Extends {@link IllegalStateException} so callers and tests that match on the broader
 * type still work.
 *
 * <p>The companion {@link CryptoSecretFailureAnalyzer} matches on this exact type and
 * substitutes a clean operator-facing banner for the default Spring Boot stack trace
 * during startup, with the {@link Reason} driving the description and remediation hint.
 */
public class CryptoSecretConfigurationException extends IllegalStateException {

    /** Distinguishes the four failure modes we want to surface differently to operators. */
    public enum Reason {
        /** Property absent or blank — no value at all. */
        UNSET,
        /** The literal placeholder shipped in {@code .env.example} was never replaced. */
        PLACEHOLDER,
        /** Value is set but does not decode under the standard Base64 alphabet. */
        NOT_BASE64,
        /** Value decodes successfully but to the wrong number of bytes. */
        WRONG_LENGTH
    }

    private final Reason reason;
    private final int decodedLength;

    public CryptoSecretConfigurationException(final Reason reason, final String message) {
        this(reason, -1, message);
    }

    public CryptoSecretConfigurationException(final Reason reason, final int decodedLength,
                                              final String message) {
        super(message);
        this.reason = reason;
        this.decodedLength = decodedLength;
    }

    public Reason getReason() {
        return reason;
    }

    /** Number of bytes the value decoded to; only meaningful when {@link #reason} is
     *  {@link Reason#WRONG_LENGTH}. {@code -1} otherwise. */
    public int getDecodedLength() {
        return decodedLength;
    }
}
