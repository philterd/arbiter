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

import ai.philterd.arbiter.util.Hashing;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultPhilterClientFactory implements PhilterClientFactory {

    // One SDK client (and its HTTP connection pool) per instance and key, reused across documents.
    private final Map<ClientKey, ai.philterd.philter.PhilterClient> clients = new ConcurrentHashMap<>();
    private final PhilterTlsConfig tls;

    public DefaultPhilterClientFactory(final PhilterTlsConfig tls) {
        this.tls = tls;
    }

    @Override
    public PhilterClient create(final String philterUrl, final String apiKey, final String policyName) {
        return create(philterUrl, apiKey, null, policyName);
    }

    @Override
    public PhilterClient create(final String philterUrl, final String apiKey, final SSLContext sslContext,
                                final String policyName) {
        final SSLContext context = sslContext == null ? tls.sslContext() : sslContext;
        // An instance's own context is cached by its caller until its stores change, and SSLContext compares
        // by identity, so the key tells one instance's TLS settings from another's and from a replaced certificate.
        final ClientKey cacheKey = new ClientKey(philterUrl, apiKey == null ? "" : Hashing.sha512Hex(apiKey), context);
        return new PhilterClientImpl(
                clients.computeIfAbsent(cacheKey, k -> PhilterClients.build(philterUrl, apiKey, context)), policyName);
    }

    private record ClientKey(String url, String apiKeyHash, SSLContext sslContext) {
    }
}
