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

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SymmetricCipher(@Value("${arbiter.crypto.secret:}") final String configured) {
        this.key = new SecretKeySpec(loadKey(configured), "AES");
    }

    /**
     * Decode and validate the configured secret. Throws {@link IllegalStateException}
     * with a deployment-friendly message on any failure so the operator sees a clear
     * startup error instead of a silent fallback to a weak key.
     */
    private static byte[] loadKey(final String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "arbiter.crypto.secret is not set. Set it to a base64-encoded 32-byte "
                            + "random value before starting Arbiter (e.g. `openssl rand -base64 32`). "
                            + "There is no longer a development fallback — the application will "
                            + "not start without a properly-formed key.");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "arbiter.crypto.secret must be base64-encoded. Generate one with "
                            + "`openssl rand -base64 32`. Passphrases and other formats are "
                            + "no longer accepted.");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "arbiter.crypto.secret decoded to " + decoded.length + " bytes; expected "
                            + "exactly " + KEY_BYTES + " bytes (256-bit AES key). Generate one "
                            + "with `openssl rand -base64 32`.");
        }
        return decoded;
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
