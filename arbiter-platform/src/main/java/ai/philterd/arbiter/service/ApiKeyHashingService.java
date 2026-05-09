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

import ai.philterd.arbiter.util.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Hashes API keys with HMAC-SHA-256 keyed by {@code arbiter.crypto.secret}.
 *
 * <p>Using the server's secret as a key means a leaked database is not sufficient to
 * reverse API key hashes — an attacker also needs the secret. The same
 * {@code arbiter.crypto.secret} value that drives {@link SymmetricCipher} is reused
 * here; no additional key material is required.
 *
 * <p>Existing API keys stored as bare SHA-512 hashes will stop matching after this
 * service is deployed. Users must regenerate their keys once.
 */
@Service
public class ApiKeyHashingService {

    private final byte[] keyBytes;

    public ApiKeyHashingService(@Value("${arbiter.crypto.secret}") final String configured) {
        try {
            this.keyBytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "arbiter.crypto.secret must be base64-encoded. "
                            + "Generate one with `openssl rand -base64 32`.");
        }
        if (this.keyBytes.length != 32) {
            throw new IllegalStateException(
                    "arbiter.crypto.secret decoded to " + keyBytes.length
                            + " bytes; expected exactly 32 bytes.");
        }
    }

    public String hash(final String apiKey) {
        return Hashing.hmacSha256Hex(apiKey, keyBytes);
    }
}
