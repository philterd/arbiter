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
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Changing a document's case metadata, and auditing it (#16). */
class CaseMetadataServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final CaseMetadataService service = new CaseMetadataService(documents, audit);

    private static Document document(final CaseMetadata m) {
        final Document d = new Document();
        d.setId("d1");
        d.setStatus("FAILED");
        d.setCaseMetadata(m);
        return d;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aChangeIsSavedAndAuditedWithoutTheCaseNumberOrFiler() {
        when(audit.hashForAudit(anyString())).thenAnswer(i -> "h:" + i.getArgument(0).toString().length());
        final Document d = document(CaseMetadata.of("1:26-cv-01234", "criminal", "Acme Dental LLC", null, null));

        assertTrue(service.update(d, CaseMetadata.of("1:26-cv-09999", "civil", "Acme Dental LLC", "paid", false)));

        assertEquals("civil", d.getCaseMetadata().getCaseType());
        verify(documents).save(d);
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("DOCUMENT_CASE_METADATA_UPDATE"), eq("Document"), eq("d1"), details.capture());
        final Map<String, Object> m = details.getValue();
        assertEquals("criminal", m.get("previousCaseType"));
        assertEquals("civil", m.get("caseType"));
        assertEquals("paid", m.get("feeStatus"));
        assertEquals(false, m.get("ifp"));
        assertEquals("h:13", m.get("caseNumberHash"));
        assertFalse(m.containsKey("filerHash"), "the filer did not change");
        assertFalse(m.toString().contains("09999") || m.toString().contains("Acme"), m.toString());
    }

    @Test
    void anUnchangedOrFinalizedDocumentIsLeftAlone() {
        final Document d = document(CaseMetadata.of(null, "civil", null, null, null));
        assertFalse(service.update(d, CaseMetadata.of(null, "civil", null, null, null)));
        verify(documents, never()).save(any());

        for (String status : new String[]{"FINALIZED", "PENDING", "PROCESSING"}) {
            d.setStatus(status);
            assertThrows(CaseMetadataService.NotChangeableException.class,
                    () -> service.update(d, CaseMetadata.of(null, "criminal", null, null, null)), status);
        }
        verify(audit, never()).log(anyString(), any(), any(), any());
    }

    @Test
    void clearingTheMetadataRemovesIt() {
        final Document d = document(CaseMetadata.of(null, "civil", null, null, null));
        assertTrue(service.update(d, null));
        assertNull(d.getCaseMetadata());
    }
}
