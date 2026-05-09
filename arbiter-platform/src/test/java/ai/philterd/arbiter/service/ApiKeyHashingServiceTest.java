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

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyHashingServiceTest {

    private static final String VALID_SECRET =
            Base64.getEncoder().encodeToString(new byte[32]);  // 32 zero bytes, valid key

    private ApiKeyHashingService service(final String secret) {
        return new ApiKeyHashingService(secret);
    }

    @Test
    void sameInputProducesSameHash() {
        final ApiKeyHashingService svc = service(VALID_SECRET);
        assertEquals(svc.hash("my-api-key"), svc.hash("my-api-key"));
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        final ApiKeyHashingService svc = service(VALID_SECRET);
        assertNotEquals(svc.hash("key-one"), svc.hash("key-two"));
    }

    @Test
    void differentSecretsProduceDifferentHashesForSameInput() {
        final byte[] keyA = new byte[32];
        final byte[] keyB = new byte[32];
        keyB[0] = 1;
        final ApiKeyHashingService svcA = service(Base64.getEncoder().encodeToString(keyA));
        final ApiKeyHashingService svcB = service(Base64.getEncoder().encodeToString(keyB));
        assertNotEquals(svcA.hash("same-key"), svcB.hash("same-key"));
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(service(VALID_SECRET).hash(null));
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalStateException.class, () -> service("   "));
    }

    @Test
    void rejectsNonBase64Secret() {
        assertThrows(IllegalStateException.class, () -> service("not-base64!!!"));
    }

    @Test
    void rejectsKeyTooShort() {
        final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> service(shortKey));
    }

    @Test
    void rejectsKeyTooLong() {
        final String longKey = Base64.getEncoder().encodeToString(new byte[64]);
        assertThrows(IllegalStateException.class, () -> service(longKey));
    }

    @Test
    void rejectsLiteralPlaceholderFromEnvExample() {
        // Operators who copy .env.example to .env without editing it ship this exact
        // string. The exception must surface a distinct PLACEHOLDER reason so the
        // FailureAnalyzer can render its targeted banner (the operator-facing remediation
        // copy lives there — see CryptoSecretFailureAnalyzerTest).
        final CryptoSecretConfigurationException e = assertThrows(
                CryptoSecretConfigurationException.class,
                () -> service("replace-me-with-base64-of-32-random-bytes"));
        assertTrue(e instanceof IllegalStateException,
                "must extend IllegalStateException so older callers still match");
        assertEquals(CryptoSecretConfigurationException.Reason.PLACEHOLDER, e.getReason());
    }

    @Test
    void rejectsNullSecret() {
        // A null property — distinct from blank — must not NPE; the loader normalises null
        // to empty and reports it as UNSET rather than crashing.
        final CryptoSecretConfigurationException e = assertThrows(
                CryptoSecretConfigurationException.class, () -> service(null));
        assertEquals(CryptoSecretConfigurationException.Reason.UNSET, e.getReason());
    }

    @Test
    void hashIsNot512BitsLong() {
        // HMAC-SHA-256 produces 64 hex chars (256-bit), not 128 (SHA-512 output).
        final String hash = service(VALID_SECRET).hash("test");
        assertEquals(64, hash.length(), "HMAC-SHA-256 output must be 256 bits = 64 hex chars");
    }
}
