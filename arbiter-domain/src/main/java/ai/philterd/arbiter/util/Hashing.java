/*
 * Copyright 2026 Philterd
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
package ai.philterd.arbiter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    public static String sha512Hex(final String input) {
        if (input == null) return null;
        return sha512Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha512Hex(final byte[] input) {
        if (input == null) return null;
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-512");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private Hashing() {}
}
