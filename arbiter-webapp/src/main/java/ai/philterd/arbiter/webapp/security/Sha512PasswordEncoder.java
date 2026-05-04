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
package ai.philterd.arbiter.webapp.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Stores passwords as "<saltHex>$<sha512Hex(salt + password)>".
 * Salt is 16 random bytes (32 hex chars), regenerated on every encode().
 */
public class Sha512PasswordEncoder implements PasswordEncoder {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final char SEPARATOR = '$';

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) return null;
        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return salt + SEPARATOR + Hashing.sha512Hex(salt + rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        int sep = encodedPassword.indexOf(SEPARATOR);
        if (sep <= 0 || sep == encodedPassword.length() - 1) return false;
        String salt = encodedPassword.substring(0, sep);
        String storedHash = encodedPassword.substring(sep + 1);
        String candidate = Hashing.sha512Hex(salt + rawPassword);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
