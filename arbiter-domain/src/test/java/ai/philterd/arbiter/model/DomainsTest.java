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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainsTest {

    @Test
    void rejectsNull() {
        assertFalse(Domains.isValid(null));
    }

    @Test
    void rejectsBlank() {
        assertFalse(Domains.isValid(""));
        assertFalse(Domains.isValid("   "));
    }

    @Test
    void rejectsUnknownValue() {
        assertFalse(Domains.isValid("Cybernetics"));
    }

    @Test
    void rejectsWrongCasing() {
        // Uniqueness is exact-case to match the static list values.
        assertFalse(Domains.isValid("financial"));
        assertFalse(Domains.isValid("FINANCIAL"));
    }

    @Test
    void acceptsKnownValues() {
        assertTrue(Domains.isValid("Financial"));
        assertTrue(Domains.isValid("Healthcare"));
        assertTrue(Domains.isValid("Other"));
    }

    @Test
    void valuesListIsImmutable() {
        try {
            Domains.VALUES.add("Aerospace");
            // If we get here, it's mutable, which we do not want.
            org.junit.jupiter.api.Assertions.fail("Domains.VALUES should be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
