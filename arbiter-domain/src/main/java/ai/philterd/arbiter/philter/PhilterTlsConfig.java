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
package ai.philterd.arbiter.philter;

import ai.philterd.philter.PhilterTls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;

/**
 * TLS for connections to remote Philter instances: a client certificate to present for mutual
 * TLS, and a trust store for a Philter (or a proxy in front of it) whose certificate comes from a
 * private certificate authority. Both are optional. Without either, the JVM's default trust store
 * is used and no client certificate is presented.
 *
 * <p>The stores are read once at startup, so a missing file or wrong password stops Arbiter
 * instead of failing the first redaction.</p>
 */
@Component
public class PhilterTlsConfig {

    private static final Logger log = LoggerFactory.getLogger(PhilterTlsConfig.class);

    private final SSLContext sslContext;

    public PhilterTlsConfig(@Value("${arbiter.philter.tls.key-store:}") final String keyStore,
                            @Value("${arbiter.philter.tls.key-store-password:}") final String keyStorePassword,
                            @Value("${arbiter.philter.tls.trust-store:}") final String trustStore,
                            @Value("${arbiter.philter.tls.trust-store-password:}") final String trustStorePassword) {
        final Path key = blank(keyStore) ? null : Path.of(keyStore.trim());
        final Path trust = blank(trustStore) ? null : Path.of(trustStore.trim());
        if (key == null && trust == null) {
            this.sslContext = null;
            return;
        }
        try {
            this.sslContext = PhilterTls.sslContext(
                    key, key == null ? null : keyStorePassword.toCharArray(),
                    trust, blank(trustStorePassword) ? null : trustStorePassword.toCharArray());
        } catch (IllegalArgumentException e) {
            // The SDK's message names the file, never the password.
            throw new IllegalStateException("Philter TLS settings (arbiter.philter.tls.*) are not usable: "
                    + e.getMessage(), e);
        }
        log.info("Philter connections: client certificate {}, trust store {}",
                key == null ? "not set" : "set", trust == null ? "JVM default" : "set");
    }

    /** No client certificate and the JVM's default trust store. */
    public static PhilterTlsConfig none() {
        return new PhilterTlsConfig("", "", "", "");
    }

    /** The TLS context for Philter connections, or {@code null} for the SDK's default. */
    public SSLContext sslContext() {
        return sslContext;
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }
}
