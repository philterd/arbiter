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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TLS for connections to Philter: a client certificate to present for mutual
 * TLS, and a trust store for a Philter (or a proxy in front of it) whose certificate comes from a
 * private certificate authority. Both are optional. Without either, the JVM's default trust store
 * is used and no client certificate is presented.
 *
 * <p>The stores are read once at startup, so a missing file or wrong password stops Philter Workspace
 * instead of failing the first redaction.</p>
 */
@Component
public class PhilterTlsConfig {

    private static final Logger log = LoggerFactory.getLogger(PhilterTlsConfig.class);

    private final SSLContext sslContext;

    public PhilterTlsConfig(@Value("${philter-workspace.philter.tls.key-store:}") final String keyStore,
                            @Value("${philter-workspace.philter.tls.key-store-password:}") final String keyStorePassword,
                            @Value("${philter-workspace.philter.tls.trust-store:}") final String trustStore,
                            @Value("${philter-workspace.philter.tls.trust-store-password:}") final String trustStorePassword) {
        final Path key = blank(keyStore) ? null : Path.of(keyStore.trim());
        final Path trust = blank(trustStore) ? null : Path.of(trustStore.trim());
        final KeyManager[] keyManagers;
        final TrustManager[] trustManagers;
        try {
            keyManagers = key == null ? null
                    : PhilterKeyStores.keyManagers(read(key), chars(keyStorePassword), key.toString());
            trustManagers = trust == null ? null
                    : PhilterKeyStores.trustManagers(read(trust), chars(trustStorePassword), trust.toString());
        } catch (IllegalArgumentException e) {
            // The message names the file, never the password.
            throw new IllegalStateException("Philter TLS settings (philter-workspace.philter.tls.*) are not usable: "
                    + e.getMessage(), e);
        }
        this.sslContext = key == null && trust == null ? null : PhilterKeyStores.sslContext(keyManagers, trustManagers);
        if (sslContext != null) {
            log.info("Philter connections: client certificate {}, trust store {}",
                    key == null ? "not set" : "set", trust == null ? "JVM default" : "set");
        }
    }

    /** No client certificate and the JVM's default trust store. */
    public static PhilterTlsConfig none() {
        return new PhilterTlsConfig("", "", "", "");
    }

    /** The TLS context for Philter connections, or {@code null} for the SDK's default. */
    public SSLContext sslContext() {
        return sslContext;
    }

    private static byte[] read(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("The file " + path + " could not be read: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private static char[] chars(final String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }
}
