/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the password-encoder bean returned by {@link SecurityConfig} encodes new hashes
 * with BCrypt and still verifies legacy unprefixed SHA-512 hashes — so existing accounts
 * keep working until their password is rotated.
 */
class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void newHashesUseBcryptPrefix() {
        final String hash = encoder.encode("correct-horse-battery-staple");
        assertNotNull(hash);
        // BCrypt hashes from DelegatingPasswordEncoder are prefixed with {bcrypt}.
        assertTrue(hash.startsWith("{bcrypt}"),
                "expected {bcrypt} prefix on new hash, got: " + hash);
    }

    @Test
    void newHashesRoundTrip() {
        final String hash = encoder.encode("correct-horse-battery-staple");
        assertTrue(encoder.matches("correct-horse-battery-staple", hash));
        assertFalse(encoder.matches("wrong-password", hash));
    }

    @Test
    void legacyUnprefixedSha512HashesStillMatch() {
        // Reproduce the historical hash format ({saltHex}${sha512Hex}) using the legacy
        // encoder, then verify the new bean accepts it.
        final Sha512PasswordEncoder legacy = new Sha512PasswordEncoder();
        final String legacyHash = legacy.encode("legacy-password");
        // Sanity: it must look like the legacy format (no encoder prefix).
        assertFalse(legacyHash.startsWith("{"));
        assertTrue(encoder.matches("legacy-password", legacyHash));
        assertFalse(encoder.matches("wrong-password", legacyHash));
    }

    @Test
    void prefixedSha512HashesStillMatch() {
        // {sha512salted} prefixed hashes also work — that's how {@link DelegatingPasswordEncoder}
        // routes when the prefix is present.
        final Sha512PasswordEncoder legacy = new Sha512PasswordEncoder();
        final String prefixed = "{sha512salted}" + legacy.encode("legacy-password");
        assertTrue(encoder.matches("legacy-password", prefixed));
    }
}
