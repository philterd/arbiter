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
import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.philter.PhilterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Builds Philter SDK clients for configured {@link PhilterInstance}s.
 */
@Component
public class PhilterInstanceClients {

    private static final Logger log = LoggerFactory.getLogger(PhilterInstanceClients.class);

    private final SymmetricCipher symmetricCipher;
    private final DataSourceHostAllowList hostAllowList;
    private final PhilterInstanceTls instanceTls;

    public PhilterInstanceClients(final SymmetricCipher symmetricCipher,
                                  final DataSourceHostAllowList hostAllowList,
                                  final PhilterInstanceTls instanceTls) {
        this.symmetricCipher = symmetricCipher;
        this.hostAllowList = hostAllowList;
        this.instanceTls = instanceTls;
    }

    /** The instance's base URL, such as {@code http://philter:8080}. */
    public static String baseUrl(final PhilterInstance instance) {
        String host = instance.getEndpoint();
        if (host == null || host.isBlank()) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }

    /**
     * An SDK client for the instance, authenticated with its API key when one is set and using the
     * instance's own TLS settings where it has them. Refuses an instance whose host is not on the
     * data-source allow-list.
     */
    public PhilterClient client(final PhilterInstance instance) throws IOException {
        final String url = baseUrl(instance);
        if (!hostAllowList.isAllowed(url)) {
            throw new IOException("Philter instance \"" + instance.getName()
                    + "\" host is not on the data-source allow-list "
                    + "(arbiter.data-sources.allowed-hosts).");
        }
        return PhilterClients.build(url, apiKey(instance), instanceTls.sslContext(instance));
    }

    /**
     * Whether the instance's API key belongs to a Philter administrator, from the caller's role in
     * {@code GET /api/limits}, which any key may call whatever its scopes. Null when Philter does not
     * say.
     */
    public Boolean keyBelongsToAdministrator(final PhilterInstance instance) throws IOException {
        final ai.philterd.philter.model.Limits limits = client(instance).getLimits();
        final String role = limits == null || limits.getCaller() == null ? null : limits.getCaller().getRole();
        return role == null ? null : "admin".equalsIgnoreCase(role);
    }

    /** True when the instance has an API key that decrypts. */
    public boolean hasApiKey(final PhilterInstance instance) {
        final String apiKey = apiKey(instance);
        return apiKey != null && !apiKey.isBlank();
    }

    private String apiKey(final PhilterInstance instance) {
        if (instance.getEncryptedApiKey() == null || instance.getEncryptedApiKey().isBlank()) {
            return null;
        }
        try {
            return symmetricCipher.decrypt(instance.getEncryptedApiKey());
        } catch (Exception e) {
            // The crypto secret may have rotated; continue unauthenticated.
            log.warn("Could not decrypt API key for Philter instance \"{}\".", instance.getName());
            return null;
        }
    }
}
