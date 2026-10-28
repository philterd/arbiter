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
package ai.philterd.workspace.philter;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;

/**
 * Reads PKCS12 and JKS stores from memory for Philter TLS, so a keystore kept encrypted in the database
 * is never written to disk. Every message names the store by its label, when given, and never contains a password.
 */
public final class PhilterKeyStores {

    private PhilterKeyStores() {
    }

    /**
     * Key managers presenting the client certificate in {@code store}.
     *
     * @throws IllegalArgumentException if the store can't be read with {@code password} or holds no private key
     */
    public static KeyManager[] keyManagers(final byte[] store, final char[] password, final String label) {
        final char[] pw = password == null ? new char[0] : password;
        final String name = name("client certificate keystore", label);
        final KeyStore keyStore = load(store, pw, name);
        try {
            if (Collections.list(keyStore.aliases()).stream().noneMatch(alias -> isKeyEntry(keyStore, alias))) {
                throw new IllegalArgumentException("The " + name + " holds no private key.");
            }
            final KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, pw);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The private key in the " + name
                    + " could not be read. Its password must be the keystore's password.", e);
        }
    }

    /**
     * Trust managers trusting the certificates in {@code store}. {@code password} may be empty for a store
     * without one, in which case its integrity is not checked.
     *
     * @throws IllegalArgumentException if the store can't be read or holds no trusted certificates
     */
    public static TrustManager[] trustManagers(final byte[] store, final char[] password, final String label) {
        final String name = name("trust store", label);
        final KeyStore trustStore = load(store, password == null || password.length == 0 ? null : password, name);
        try {
            if (trustStore.size() == 0 && (password == null || password.length == 0)) {
                // A PKCS12 file from keytool encrypts its certificates, so without its password it reads as empty.
                throw new IllegalArgumentException("The " + name + " holds no certificates that can be read "
                        + "without a password. Give its password, or, if it has none, build it with keytool "
                        + "-importcert: a PKCS12 trust store made with openssl reads as empty in Java.");
            }
            if (trustStore.size() == 0) {
                // Java skips certificates without its trusted-certificate attribute, which a PKCS12 file
                // from openssl pkcs12 -nokeys lacks, so such a file reads as empty.
                throw new IllegalArgumentException("The " + name + " holds no trusted certificates. "
                        + "A PKCS12 trust store made with openssl reads as empty in Java; build it with "
                        + "keytool -importcert instead.");
            }
            final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            return factory.getTrustManagers();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The " + name + " could not be used.", e);
        }
    }

    /**
     * A TLS context from the given managers. {@code null} key managers present no client certificate, and
     * {@code null} trust managers trust the JVM's default trust store.
     */
    public static SSLContext sslContext(final KeyManager[] keyManagers, final TrustManager[] trustManagers) {
        try {
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JVM could not create a TLS context.", e);
        }
    }

    /** {@code kind} followed by {@code label}, such as a file name, when there is one. */
    private static String name(final String kind, final String label) {
        return label == null || label.isBlank() ? kind : kind + " " + label;
    }

    private static KeyStore load(final byte[] store, final char[] password, final String what) {
        if (store == null || store.length == 0) {
            throw new IllegalArgumentException("The " + what + " is empty.");
        }
        try {
            final KeyStore keyStore = KeyStore.getInstance(type(store));
            keyStore.load(new ByteArrayInputStream(store), password);
            return keyStore;
        } catch (IOException | GeneralSecurityException e) {
            // The JDK's messages name neither the store nor the password.
            throw new IllegalArgumentException("The " + what + " could not be read. Check that it is a PKCS12 "
                    + "or JKS file and that the password is correct: " + e.getMessage(), e);
        }
    }

    /** JKS and JCEKS files start with a magic number; anything else is read as PKCS12. */
    private static String type(final byte[] store) {
        if (store.length >= 4) {
            final int magic = ((store[0] & 0xff) << 24) | ((store[1] & 0xff) << 16) | ((store[2] & 0xff) << 8) | (store[3] & 0xff);
            if (magic == 0xFEEDFEED) {
                return "JKS";
            }
            if (magic == 0xCECECECE) {
                return "JCEKS";
            }
        }
        return "PKCS12";
    }

    private static boolean isKeyEntry(final KeyStore store, final String alias) {
        try {
            return store.isKeyEntry(alias);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
