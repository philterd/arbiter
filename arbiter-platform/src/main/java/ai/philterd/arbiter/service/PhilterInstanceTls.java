/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterKeyStores;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.util.Hashing;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Philter instance's own client certificate and trust store: validating and encrypting them when an
 * admin attaches them, and building the instance's TLS context from them. A store the instance doesn't
 * have falls back to the global {@code arbiter.philter.tls.*} setting.
 */
@Component
public class PhilterInstanceTls {

    private final SymmetricCipher symmetricCipher;
    private final PhilterTlsConfig globalTls;

    // Instance id -> its context, rebuilt when the instance's encrypted stores change.
    private final Map<String, CachedContext> contexts = new ConcurrentHashMap<>();

    public PhilterInstanceTls(final SymmetricCipher symmetricCipher, final PhilterTlsConfig globalTls) {
        this.symmetricCipher = symmetricCipher;
        this.globalTls = globalTls;
    }

    /**
     * Validate {@code store} as a client certificate keystore and attach it to {@code instance}, encrypted.
     *
     * @throws IllegalArgumentException naming the problem, never the password, if the keystore can't be used
     */
    public void setKeyStore(final PhilterInstance instance, final byte[] store, final String password) {
        PhilterKeyStores.keyManagers(store, chars(password), null);
        instance.setEncryptedKeyStore(symmetricCipher.encrypt(Base64.getEncoder().encodeToString(store)));
        instance.setEncryptedKeyStorePassword(symmetricCipher.encrypt(password == null ? "" : password));
    }

    /**
     * Validate {@code store} as a trust store and attach it to {@code instance}, encrypted.
     *
     * @throws IllegalArgumentException naming the problem, never the password, if the trust store can't be used
     */
    public void setTrustStore(final PhilterInstance instance, final byte[] store, final String password) {
        PhilterKeyStores.trustManagers(store, chars(password), null);
        instance.setEncryptedTrustStore(symmetricCipher.encrypt(Base64.getEncoder().encodeToString(store)));
        instance.setEncryptedTrustStorePassword(symmetricCipher.encrypt(password == null ? "" : password));
    }

    public void clearKeyStore(final PhilterInstance instance) {
        instance.setEncryptedKeyStore(null);
        instance.setEncryptedKeyStorePassword(null);
    }

    public void clearTrustStore(final PhilterInstance instance) {
        instance.setEncryptedTrustStore(null);
        instance.setEncryptedTrustStorePassword(null);
    }

    public static boolean hasKeyStore(final PhilterInstance instance) {
        return notBlank(instance.getEncryptedKeyStore());
    }

    public static boolean hasTrustStore(final PhilterInstance instance) {
        return notBlank(instance.getEncryptedTrustStore());
    }

    /**
     * The TLS context for {@code instance}: its own stores where it has them and the global settings
     * otherwise. {@code null} means no TLS settings at all, so the SDK's default applies.
     *
     * @throws IOException if the instance's stores can't be decrypted or read, rather than quietly
     *                     connecting with the global settings in their place
     */
    public SSLContext sslContext(final PhilterInstance instance) throws IOException {
        if (!hasKeyStore(instance) && !hasTrustStore(instance)) {
            if (instance.getId() != null) {
                contexts.remove(instance.getId());
            }
            return globalTls.sslContext();
        }
        // Ciphertext gets a fresh IV on every save, so its hash changes whenever a store is replaced.
        final String fingerprint = Hashing.sha512Hex(str(instance.getEncryptedKeyStore()) + "\n"
                + str(instance.getEncryptedKeyStorePassword()) + "\n" + str(instance.getEncryptedTrustStore())
                + "\n" + str(instance.getEncryptedTrustStorePassword()));
        final CachedContext cached = instance.getId() == null ? null : contexts.get(instance.getId());
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.context();
        }
        final SSLContext context = build(instance);
        if (instance.getId() != null) {
            contexts.put(instance.getId(), new CachedContext(fingerprint, context));
        }
        return context;
    }

    private SSLContext build(final PhilterInstance instance) throws IOException {
        try {
            final KeyManager[] keys = hasKeyStore(instance)
                    ? PhilterKeyStores.keyManagers(decryptStore(instance.getEncryptedKeyStore()),
                            chars(decrypt(instance.getEncryptedKeyStorePassword())), null)
                    : null;
            final TrustManager[] trust = hasTrustStore(instance)
                    ? PhilterKeyStores.trustManagers(decryptStore(instance.getEncryptedTrustStore()),
                            chars(decrypt(instance.getEncryptedTrustStorePassword())), null)
                    : null;
            return globalTls.sslContext(keys, trust);
        } catch (RuntimeException e) {
            // A rotated arbiter.crypto.secret fails here. The message names no secret.
            throw new IOException("The TLS settings of Philter instance \"" + instance.getName()
                    + "\" could not be read; attach its client certificate and trust store again.", e);
        }
    }

    private byte[] decryptStore(final String encrypted) {
        return Base64.getDecoder().decode(symmetricCipher.decrypt(encrypted));
    }

    private String decrypt(final String encrypted) {
        return notBlank(encrypted) ? symmetricCipher.decrypt(encrypted) : "";
    }

    private static char[] chars(final String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    private static boolean notBlank(final String s) {
        return s != null && !s.isBlank();
    }

    private static String str(final String s) {
        return s == null ? "" : s;
    }

    private record CachedContext(String fingerprint, SSLContext context) {
    }
}
