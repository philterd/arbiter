/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.CaseMetadata;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.CaseMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reading and replacing a document's case metadata through the API (#16). */
class CaseMetadataControllerTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final BatchRepository batches = mock(BatchRepository.class);
    private final CaseMetadataService service = mock(CaseMetadataService.class);
    private final TestDoubles.FakeUserGroups groups = TestDoubles.userGroups();
    private CaseMetadataController controller;
    private Document document;

    @BeforeEach
    void setUp() {
        controller = new CaseMetadataController(documents,
                TestDoubles.documentAccess(batches, documents, groups), service);
        document = new Document();
        document.setId("d1");
        document.setBatchId("b1");
        document.setCaseMetadata(CaseMetadata.of(null, "civil", null, null, null));
        document.setAppliedPolicyName("civil-filings");
        document.setAppliedPolicyVersion(2);
        when(documents.findById("d1")).thenReturn(Optional.of(document));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batches.findById("b1")).thenReturn(Optional.of(batch));
        groups.withMembership("rob@example.com", Set.of("g1"));
    }

    @Test
    void theMetadataAndAppliedPolicyAreRead() {
        final Map<String, Object> body = controller.get("d1", TestAuth.user("rob@example.com"));
        assertEquals(document.getCaseMetadata(), body.get("caseMetadata"));
        assertEquals("civil-filings", body.get("appliedPolicyName"));
        assertEquals(2, body.get("appliedPolicyVersion"));
    }

    @Test
    void aChangeIsValidatedThenMade() {
        when(service.update(any(), any())).thenReturn(true);
        final ResponseEntity<Map<String, Object>> ok = controller.put("d1",
                new CaseMetadata(null, " Criminal ", null, null, null), TestAuth.user("rob@example.com"));
        assertEquals(200, ok.getStatusCode().value());
        verify(service).update(org.mockito.ArgumentMatchers.eq(document),
                org.mockito.ArgumentMatchers.argThat(m -> "criminal".equals(m.getCaseType())));

        final ResponseEntity<Map<String, Object>> bad = controller.put("d1",
                new CaseMetadata(null, "civil", null, "free", null), TestAuth.user("rob@example.com"));
        assertEquals(400, bad.getStatusCode().value());
    }

    @Test
    void aFinalizedDocumentIsAConflictAndAnOutsiderCannotSeeIt() {
        when(service.update(any(), any())).thenThrow(new CaseMetadataService.NotChangeableException("final"));
        assertEquals(409, controller.put("d1", new CaseMetadata(), TestAuth.user("rob@example.com"))
                .getStatusCode().value());

        groups.withMembership("oz@example.com", Set.of("g2"));
        assertThrows(ResponseStatusException.class,
                () -> controller.put("d1", new CaseMetadata(), TestAuth.user("oz@example.com")));
        assertThrows(ResponseStatusException.class, () -> controller.get("d1", TestAuth.user("oz@example.com")));
        verify(service, org.mockito.Mockito.times(1)).update(any(), any());
        verify(documents, never()).save(any());
    }

    @Test
    void theApiSerializesOnlyTheSchemasFields() throws Exception {
        final String json = new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(CaseMetadata.of("1:26-cv-01234", "civil", "Acme Dental LLC", "paid", false));
        final java.util.Set<String> fields = new java.util.TreeSet<>();
        fields.addAll(new tools.jackson.databind.ObjectMapper().readTree(json).propertyNames());
        assertEquals(java.util.Set.of("caseNumber", "caseType", "feeStatus", "filer", "ifp"), fields);
    }
}
