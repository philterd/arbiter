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

import ai.philterd.philter.model.exceptions.PhilterError;

import javax.net.ssl.SSLContext;

/**
 * Builds Philter SDK clients for remote Philter instances.
 */
public final class PhilterClients {

    private PhilterClients() {
    }

    /**
     * Build an SDK client for the instance at {@code baseUrl}. A non-blank {@code apiKey} is sent as a
     * bearer token, and {@code tls} supplies any client certificate and trust store.
     */
    public static ai.philterd.philter.PhilterClient build(final String baseUrl, final String apiKey,
                                                          final PhilterTlsConfig tls) {
        return build(baseUrl, apiKey, tls == null ? null : tls.sslContext());
    }

    /**
     * Build an SDK client for the instance at {@code baseUrl} that uses {@code sslContext}, or the SDK's
     * default TLS when it is {@code null}.
     */
    public static ai.philterd.philter.PhilterClient build(final String baseUrl, final String apiKey,
                                                          final SSLContext sslContext) {
        final ai.philterd.philter.PhilterClient.PhilterClientBuilder builder =
                new ai.philterd.philter.PhilterClient.PhilterClientBuilder().withEndpoint(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey("Bearer " + apiKey);
        }
        if (sslContext != null) {
            builder.withSslContext(sslContext);
        }
        return builder.build();
    }

    /**
     * Describe a failed Philter call by status, reason, and request id only, so Philter's response
     * text never reaches a log or a message shown to the user.
     */
    public static String describe(final Exception e) {
        if (e instanceof PhilterError error) {
            final StringBuilder sb = new StringBuilder();
            sb.append(error.getStatusCode() > 0 ? "HTTP " + error.getStatusCode() : e.getClass().getSimpleName());
            if (error.getReason() != null) {
                sb.append(" (").append(error.getReason()).append(')');
            }
            if (error.getRequestId() != null) {
                sb.append(", request id ").append(error.getRequestId());
            }
            return sb.toString();
        }
        return e.getClass().getSimpleName();
    }
}
