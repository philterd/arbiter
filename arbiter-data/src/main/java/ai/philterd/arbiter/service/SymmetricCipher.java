/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM cipher for encrypting at-rest secrets such as Philter instance API keys. The key is
 * loaded from {@code arbiter.crypto.secret} (base64 of 32 raw bytes preferred, or any non-empty
 * string that gets SHA-256-derived to 32 bytes). If unset, a deterministic dev key is used
 * with a warning — fine for local development, never for production.
 *
 * Output format for {@link #encrypt(String)} is {@code base64(iv || ciphertext+tag)}: a 12-byte
 * random IV concatenated with the GCM ciphertext (which already includes its 16-byte auth tag).
 */
@Service
public class SymmetricCipher {

    private static final Logger log = LoggerFactory.getLogger(SymmetricCipher.class);

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SymmetricCipher(@Value("${arbiter.crypto.secret:}") final String configured) {
        this.key = new SecretKeySpec(deriveKey(configured), "AES");
    }

    private static byte[] deriveKey(final String configured) {
        final byte[] raw;
        if (configured != null && !configured.isBlank()) {
            // Try base64 of exactly 32 bytes; fall back to SHA-256 of the raw configured value.
            try {
                final byte[] b64 = Base64.getDecoder().decode(configured.trim());
                if (b64.length == 32) return b64;
            } catch (IllegalArgumentException ignored) {
                // Not base64; treat as a passphrase below.
            }
            return sha256(configured.trim().getBytes(StandardCharsets.UTF_8));
        }
        log.warn("arbiter.crypto.secret is not set — using an insecure deterministic dev key. "
                + "Set this environment variable in any non-development deployment.");
        return sha256("arbiter-default-development-key-do-not-use-in-production"
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(final byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String encrypt(final String plaintext) {
        if (plaintext == null) return null;
        try {
            final byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            final byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            final ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(bb.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(final String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            final byte[] in = Base64.getDecoder().decode(ciphertext);
            if (in.length <= IV_BYTES) {
                throw new IllegalStateException("Ciphertext too short");
            }
            final byte[] iv = new byte[IV_BYTES];
            final byte[] ct = new byte[in.length - IV_BYTES];
            System.arraycopy(in, 0, iv, 0, IV_BYTES);
            System.arraycopy(in, IV_BYTES, ct, 0, ct.length);
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
