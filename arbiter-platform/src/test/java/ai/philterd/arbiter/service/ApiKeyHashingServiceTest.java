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
    void hashIsNot512BitsLong() {
        // HMAC-SHA-256 produces 64 hex chars (256-bit), not 128 (SHA-512 output).
        final String hash = service(VALID_SECRET).hash("test");
        assertEquals(64, hash.length(), "HMAC-SHA-256 output must be 256 bits = 64 hex chars");
    }
}
