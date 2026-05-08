/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiiWeightsTest {

    @Test
    void defaultsAsSpecified() {
        assertEquals(10, PiiWeights.weightFor("ssn", null));
        assertEquals(10, PiiWeights.weightFor("credit-card", null));
        assertEquals(5, PiiWeights.weightFor("phone-number", null));
        assertEquals(5, PiiWeights.weightFor("email-address", null));
        assertEquals(3, PiiWeights.weightFor("person", null));
        assertEquals(3, PiiWeights.weightFor("first-name", null));
        assertEquals(3, PiiWeights.weightFor("surname", null));
        assertEquals(3, PiiWeights.weightFor("physician-name", null));
        assertEquals(3, PiiWeights.weightFor("street-address", null));
        assertEquals(2, PiiWeights.weightFor("zip-code", null));
    }

    @Test
    void unknownTypeDefaultsToOne() {
        assertEquals(1, PiiWeights.weightFor("date", null));
        assertEquals(1, PiiWeights.weightFor("flying-saucer", null));
    }

    @Test
    void overrideTakesPrecedenceOverDefault() {
        final Map<String, Integer> overrides = Map.of("ssn", 1, "phone-number", 99);
        assertEquals(1, PiiWeights.weightFor("ssn", overrides));
        assertEquals(99, PiiWeights.weightFor("phone-number", overrides));
        // Non-overridden type still uses the default.
        assertEquals(10, PiiWeights.weightFor("credit-card", overrides));
    }

    @Test
    void negativeOverrideIsClampedToZero() {
        final Map<String, Integer> overrides = Map.of("ssn", -5);
        assertEquals(0, PiiWeights.weightFor("ssn", overrides));
    }

    @Test
    void typeMatchingIsCaseInsensitive() {
        assertEquals(10, PiiWeights.weightFor("SSN", null));
        assertEquals(5, PiiWeights.weightFor("Phone-Number", null));
    }

    @Test
    void nullTypeReturnsFallback() {
        assertEquals(1, PiiWeights.weightFor(null, null));
    }

    @Test
    void effectiveCoversEveryKnownType() {
        final Map<String, Integer> map = PiiWeights.effective(null);
        assertEquals(PiiTypes.values().size(), map.size());
        for (String type : PiiTypes.values()) {
            assertTrue(map.containsKey(type), "missing weight for " + type);
            assertTrue(map.get(type) >= 0);
        }
    }

    @Test
    void effectiveAppliesOverrides() {
        final Map<String, Integer> map = PiiWeights.effective(Map.of("ssn", 1, "zip-code", 7));
        assertEquals(1, map.get("ssn"));
        assertEquals(7, map.get("zip-code"));
        assertEquals(10, map.get("credit-card")); // still default
        assertEquals(1, map.get("date"));         // still fallback
    }

    @Test
    void defaultsMapIsImmutable() {
        final Map<String, Integer> defaults = PiiWeights.defaults();
        assertThrows(UnsupportedOperationException.class, () -> defaults.put("x", 1));
    }
}
