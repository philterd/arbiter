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
package ai.philterd.arbiter.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HashingTest {

    // Known SHA-512 test vectors (lowercase hex).
    // Empty string vector from FIPS 180-4 examples.
    private static final String EMPTY_SHA512 =
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                    + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e";

    // SHA-512("abc") test vector from FIPS 180-4.
    private static final String ABC_SHA512 =
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                    + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";

    @Test
    void hashesEmptyString() {
        assertEquals(EMPTY_SHA512, Hashing.sha512Hex(""));
    }

    @Test
    void hashesAbc() {
        assertEquals(ABC_SHA512, Hashing.sha512Hex("abc"));
    }

    @Test
    void nullStringReturnsNull() {
        assertNull(Hashing.sha512Hex((String) null));
    }

    @Test
    void nullBytesReturnsNull() {
        assertNull(Hashing.sha512Hex((byte[]) null));
    }

    @Test
    void byteOverloadAgreesWithStringOverload() {
        final String s = "hello world";
        assertEquals(Hashing.sha512Hex(s),
                Hashing.sha512Hex(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void outputIsDeterministic() {
        final String a = Hashing.sha512Hex("hello world");
        final String b = Hashing.sha512Hex("hello world");
        assertEquals(a, b);
    }

    @Test
    void outputIsLowercase128Hex() {
        final String h = Hashing.sha512Hex("anything");
        assertNotNull(h);
        assertEquals(128, h.length());
        assertEquals(h.toLowerCase(), h);
        for (int i = 0; i < h.length(); i++) {
            final char c = h.charAt(i);
            final boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) throw new AssertionError("Non-hex char: " + c);
        }
    }
}
