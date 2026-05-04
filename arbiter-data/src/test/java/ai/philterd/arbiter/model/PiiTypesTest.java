package ai.philterd.arbiter.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiTypesTest {

    @Test
    void specialLabelsOverrideDerivation() {
        assertEquals("SSN", PiiTypes.labelFor("ssn"));
        assertEquals("VIN", PiiTypes.labelFor("vin"));
        assertEquals("URL", PiiTypes.labelFor("url"));
        assertEquals("Identifier", PiiTypes.labelFor("id"));
        assertEquals("IBAN Code", PiiTypes.labelFor("iban-code"));
        assertEquals("IP Address", PiiTypes.labelFor("ip-address"));
        assertEquals("MAC Address", PiiTypes.labelFor("mac-address"));
        assertEquals("PHEye", PiiTypes.labelFor("pheye"));
    }

    @Test
    void hyphenatedValuesGetTitleCase() {
        assertEquals("Phone Number", PiiTypes.labelFor("phone-number"));
        assertEquals("Email Address", PiiTypes.labelFor("email-address"));
        assertEquals("First Name", PiiTypes.labelFor("first-name"));
        assertEquals("Bank Routing Number", PiiTypes.labelFor("bank-routing-number"));
    }

    @Test
    void singleWordCapitalized() {
        assertEquals("Age", PiiTypes.labelFor("age"));
        assertEquals("Surname", PiiTypes.labelFor("surname"));
        assertEquals("Date", PiiTypes.labelFor("date"));
    }

    @Test
    void labelForNullReturnsEmpty() {
        assertEquals("", PiiTypes.labelFor(null));
    }

    @Test
    void labelForUnknownStillDerives() {
        // Not in the canonical list, but the formatter still works.
        assertEquals("Made Up Type", PiiTypes.labelFor("made-up-type"));
    }

    @Test
    void isValidChecksMembership() {
        assertTrue(PiiTypes.isValid("ssn"));
        assertTrue(PiiTypes.isValid("phone-number"));
        assertFalse(PiiTypes.isValid("not-a-real-type"));
        assertFalse(PiiTypes.isValid(""));
        assertFalse(PiiTypes.isValid(null));
    }

    @Test
    void labelsCoversEveryKnownValue() {
        final Map<String, String> labels = PiiTypes.labels();
        assertEquals(PiiTypes.values().size(), labels.size());
        for (String value : PiiTypes.values()) {
            assertTrue(labels.containsKey(value), "missing label for " + value);
            assertFalse(labels.get(value).isBlank(), "empty label for " + value);
        }
    }

    @Test
    void labelsAreSortedAlphabeticallyByLabel() {
        // First entry in the LinkedHashMap should be "Age" because it sorts
        // first alphabetically.
        final Map<String, String> labels = PiiTypes.labels();
        final String firstLabel = labels.values().iterator().next();
        assertEquals("Age", firstLabel);
    }
}
