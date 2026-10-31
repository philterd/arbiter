/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.CaseMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case metadata read from a sidecar file beside a data source's document (#16). */
class CaseMetadataSidecarTest {

    private static CaseMetadata parse(final String json) {
        return CaseMetadataSidecar.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aSidecarIsNamedAfterItsDocument() {
        assertEquals("motion.pdf.case.json", CaseMetadataSidecar.sidecarFor("motion.pdf"));
        assertTrue(CaseMetadataSidecar.isSidecar("motion.pdf.case.json"));
        assertFalse(CaseMetadataSidecar.isSidecar("motion.pdf"));
        assertFalse(CaseMetadataSidecar.isSidecar("case.json"));
    }

    @Test
    void everyFieldIsRead() {
        final CaseMetadata m = parse("""
                {"caseNumber": "1:26-cv-01234", "caseType": "Civil", "filer": "Acme Dental LLC",
                 "feeStatus": "waived", "ifp": true}""");
        assertEquals("1:26-cv-01234", m.getCaseNumber());
        assertEquals("civil", m.getCaseType());
        assertEquals("Acme Dental LLC", m.getFiler());
        assertEquals("waived", m.getFeeStatus());
        assertEquals(true, m.getIfp());
        assertNull(parse("{}"));
        assertNull(parse("{\"ifp\": null}"));
    }

    @Test
    void aBadSidecarIsRefusedWithoutQuotingItsValues() {
        for (String json : new String[]{"not json", "[1]", "{\"caseNo\": \"x\"}", "{\"ifp\": \"yes\"}",
                "{\"caseType\": 7}", "{\"feeStatus\": \"Jane Roe\"}", "{\"filer\": \"" + "J".repeat(201) + "\"}"}) {
            final CaseMetadataSidecar.InvalidSidecarException e =
                    assertThrows(CaseMetadataSidecar.InvalidSidecarException.class, () -> parse(json), json);
            assertFalse(e.getMessage().contains("Jane Roe") || e.getMessage().contains("JJJ"), e.getMessage());
        }
        assertThrows(CaseMetadataSidecar.InvalidSidecarException.class,
                () -> CaseMetadataSidecar.parse(new byte[CaseMetadataSidecar.MAX_BYTES + 1]));
    }
}
