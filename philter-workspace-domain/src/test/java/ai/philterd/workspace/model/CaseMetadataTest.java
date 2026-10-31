/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case metadata supplied with a filing (#16). */
class CaseMetadataTest {

    @Test
    void valuesAreTrimmedAndTheCaseTypeAndFeeStatusLowerCased() {
        final CaseMetadata m = CaseMetadata.of(" 1:26-cv-01234 ", " Civil ", " Acme Dental LLC ", " PAID ", false);
        assertEquals("1:26-cv-01234", m.getCaseNumber());
        assertEquals("civil", m.getCaseType());
        assertEquals("Acme Dental LLC", m.getFiler());
        assertEquals("paid", m.getFeeStatus());
        assertEquals(false, m.getIfp());
    }

    @Test
    void blanksAreNullAndNothingGivenIsNoMetadata() {
        assertNull(CaseMetadata.of(" ", "", null, "  ", null));
        final CaseMetadata m = CaseMetadata.of(null, "criminal", " ", null, null);
        assertEquals("criminal", m.getCaseType());
        assertNull(m.getFiler());
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        final CaseMetadata.InvalidException e = assertThrows(CaseMetadata.InvalidException.class,
                () -> CaseMetadata.of("x".repeat(101), "civil/appeal", "y".repeat(201), "free", null));
        assertEquals(4, e.problems().size(), e.problems().toString());
        assertTrue(e.getMessage().contains("paid, unpaid, or waived"));
    }

    @Test
    void caseTypesAreShortNames() {
        for (String ok : List.of("civil", "bankruptcy", "civil rights", "habeas-corpus", "type_2")) {
            assertEquals(ok, CaseMetadata.of(null, ok, null, null, null).getCaseType());
        }
        for (String bad : List.of("-civil", "civil.appeal", "c".repeat(65), "civil$")) {
            assertThrows(CaseMetadata.InvalidException.class, () -> CaseMetadata.of(null, bad, null, null, null), bad);
        }
    }

    @Test
    void theMappingIsLookedUpByNormalizedCaseType() {
        final GeneralSettings settings = new GeneralSettings();
        settings.getCaseTypePolicies().add(new CaseTypePolicy("civil", "civil-filings"));
        assertEquals("civil-filings", settings.policyForCaseType(" CIVIL "));
        assertNull(settings.policyForCaseType("criminal"));
        assertNull(settings.policyForCaseType(null));
    }
}
