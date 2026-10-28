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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultPhilterClientFactory implements PhilterClientFactory {

    // One SDK client (and its HTTP connection pool) per URL and key, reused across documents.
    private final Map<ClientKey, ai.philterd.philter.PhilterClient> clients = new ConcurrentHashMap<>();
    private final PhilterTlsConfig tls;

    public DefaultPhilterClientFactory(final PhilterTlsConfig tls) {
        this.tls = tls;
    }

    @Override
    public PhilterClient create(final String philterUrl, final String apiKey, final String policyName) {
        final ClientKey cacheKey = new ClientKey(philterUrl, apiKey == null ? "" : Hashing.sha512Hex(apiKey));
        return new PhilterClientImpl(
                clients.computeIfAbsent(cacheKey, k -> PhilterClients.build(philterUrl, apiKey, tls)), policyName);
    }

    private record ClientKey(String url, String apiKeyHash) {
    }
}
