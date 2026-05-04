package ai.philterd.arbiter.webapp.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sha512PasswordEncoderTest {

    private final Sha512PasswordEncoder encoder = new Sha512PasswordEncoder();

    @Test
    void encodeProducesSaltDollarHashFormat() {
        final String encoded = encoder.encode("hunter2");
        assertNotNull(encoded);
        final int sep = encoded.indexOf('$');
        assertTrue(sep > 0, "no $ separator: " + encoded);

        final String salt = encoded.substring(0, sep);
        final String hash = encoded.substring(sep + 1);
        // 16 random bytes → 32 hex chars
        assertEquals(32, salt.length());
        // SHA-512 → 64 bytes → 128 hex chars
        assertEquals(128, hash.length());
    }

    @Test
    void encodeOfSamePasswordProducesDifferentValues() {
        final String a = encoder.encode("hunter2");
        final String b = encoder.encode("hunter2");
        assertNotEquals(a, b, "salt should make repeated encodes differ");
    }

    @Test
    void matchesAcceptsCorrectPassword() {
        final String encoded = encoder.encode("correct horse battery staple");
        assertTrue(encoder.matches("correct horse battery staple", encoded));
    }

    @Test
    void matchesRejectsWrongPassword() {
        final String encoded = encoder.encode("hunter2");
        assertFalse(encoder.matches("hunter3", encoded));
    }

    @Test
    void matchesRejectsNullInputs() {
        final String encoded = encoder.encode("hunter2");
        assertFalse(encoder.matches(null, encoded));
        assertFalse(encoder.matches("hunter2", null));
    }

    @Test
    void matchesRejectsLegacyUnsaltedHash() {
        // Old-format hash without "$" separator must not validate.
        final String legacy = Hashing.sha512Hex("hunter2");
        assertFalse(legacy.contains("$"));
        assertFalse(encoder.matches("hunter2", legacy));
    }

    @Test
    void matchesRejectsTruncatedStoredValue() {
        assertFalse(encoder.matches("hunter2", "no-separator-here"));
        assertFalse(encoder.matches("hunter2", "$"));
        assertFalse(encoder.matches("hunter2", "salt$"));
    }

    @Test
    void encodeOfNullReturnsNull() {
        assertNull(encoder.encode(null));
    }

    @Test
    void crossInstancesInteroperate() {
        // A second encoder instance must verify what the first produced.
        final Sha512PasswordEncoder a = new Sha512PasswordEncoder();
        final Sha512PasswordEncoder b = new Sha512PasswordEncoder();
        final String encoded = a.encode("hunter2");
        assertTrue(b.matches("hunter2", encoded));
    }
}
