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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM cipher for encrypting at-rest secrets such as Philter instance API keys and
 * data-source credentials. The AES-256 key is loaded from {@code arbiter.crypto.secret},
 * which must be a <strong>base64-encoded value of exactly 32 random bytes</strong>.
 *
 * <p>Generate a key with one of:
 * <pre>
 *   openssl rand -base64 32
 *   head -c 32 /dev/urandom | base64
 * </pre>
 *
 * <p>Anything else — an unset property, a passphrase, base64 of the wrong length —
 * fails fast at bean construction with a descriptive message. The application will not
 * start until a properly-formed key is provided. This rules out two prior failure
 * modes: silently using a public deterministic dev key when the property is missing,
 * and using a low-entropy passphrase that can be brute-forced from a leaked database.
 *
 * <p>Output format for {@link #encrypt(String)} is {@code base64(iv || ciphertext+tag)}:
 * a 12-byte random IV concatenated with the GCM ciphertext (which already includes its
 * 16-byte auth tag).
 */
@Service
public class SymmetricCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    /**
     * Marker prefix on PII-bearing fields that have been encrypted at rest. Stored on the
     * value itself so the read path can transparently distinguish a ciphertext blob from
     * a legacy plaintext value written before encryption was switched on. The {@code v1}
     * version segment leaves room for a future re-keying or algorithm upgrade.
     */
    public static final String FIELD_PREFIX = "enc:v1:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SymmetricCipher(@Value("${arbiter.crypto.secret:}") final String configured) {
        // Validation lives in CryptoSecretLoader so it can't drift between this and
        // ApiKeyHashingService; the typed CryptoSecretConfigurationException it throws
        // is caught by CryptoSecretFailureAnalyzer to render the startup banner instead
        // of a stack trace.
        this.key = new SecretKeySpec(CryptoSecretLoader.load(configured), "AES");
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

    /**
     * Encrypt a free-form PII-bearing field for at-rest storage. The output carries a
     * {@link #FIELD_PREFIX} marker so {@link #decryptField(String)} can distinguish a
     * value written by the encrypted path from a legacy plaintext value written before
     * field encryption was turned on. Returns the input unchanged when it is null or
     * empty (an empty value carries no PII to protect, and serializing it as ciphertext
     * just makes the database harder to reason about). Idempotent for already-encrypted
     * input — the prefix is detected and the value is returned as-is, so a stray re-save
     * of an already-loaded entity does not double-encrypt the field.
     */
    public String encryptField(final String plaintext) {
        if (plaintext == null) return null;
        if (plaintext.isEmpty()) return plaintext;
        if (plaintext.startsWith(FIELD_PREFIX)) {
            // Already encrypted — defensive against an entity being saved twice without
            // an intervening load (the AfterSaveCallback restores plaintext but a misuse
            // pattern that bypasses the callbacks must still not corrupt the value).
            return plaintext;
        }
        return FIELD_PREFIX + encrypt(plaintext);
    }

    /**
     * Decrypt a field value previously written with {@link #encryptField(String)}. Values
     * without the {@link #FIELD_PREFIX} marker are returned as-is so legacy plaintext rows
     * (and the empty / null cases) keep working transparently. This is what makes the
     * roll-out backwards compatible: existing data continues to read correctly while
     * newly-written rows are encrypted.
     */
    public String decryptField(final String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(FIELD_PREFIX)) return stored;
        return decrypt(stored.substring(FIELD_PREFIX.length()));
    }

}
