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

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/** What ingest keeps from a redaction by Philter, including on a retry (#59). */
class RedactionPersistenceServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final RedactionPersistenceService ingest =
            new RedactionPersistenceService(documents, spans, mock(OpenSearchIndexService.class));

    @Test
    void aRetriedDocumentKeepsPhiltersNewIdLosesItsFailureAndHasItsSpansReplaced() {
        final Document doc = new Document();
        doc.setId("d1");
        doc.setStatus("PENDING");
        doc.setFailureMessage("Philter explain call failed: HTTP 503");
        doc.recordPhilterDocumentId("from-the-failed-attempt");

        final String text = "Call Jane Roe.";
        final RedactionResponse response = new RedactionResponse(text, text,
                List.of(new Redaction("r1", "Jane Roe", 5, 13, "person", 0.97)));
        response.setPhilterDocumentId("from-this-attempt");

        ingest.apply(doc, new Batch(), response);

        assertEquals("from-this-attempt", doc.getPhilterDocumentId());
        assertEquals(List.of("from-the-failed-attempt"), doc.getPreviousPhilterDocumentIds());
        assertNull(doc.getFailureMessage());
        // Spans left by an attempt that failed after saving them are removed before the new ones are saved.
        final InOrder order = inOrder(spans);
        order.verify(spans).deleteByDocumentId("d1");
        order.verify(spans).saveAll(anyList());
    }
}
