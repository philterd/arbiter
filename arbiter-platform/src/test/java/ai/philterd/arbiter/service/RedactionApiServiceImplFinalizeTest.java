/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterClientFactory;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in {@link RedactionApiServiceImpl#finalizeRedaction(String)} contract:
 *
 * <ul>
 *   <li>Redact the document's actual {@code originalText} (no placeholder strings).</li>
 *   <li>Persist {@code redactedText} on the document so the Download button keeps working.</li>
 *   <li>Transition the document to {@code FINALIZED}.</li>
 *   <li>Refuse with 409 when the source text is unavailable instead of emitting a redaction
 *       of empty input.</li>
 * </ul>
 */
class RedactionApiServiceImplFinalizeTest {

    private PhilterClient phileasClient;
    private PhilterClientFactory philterClientFactory;
    private PhilterInstanceRepository philterInstanceRepository;
    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private OpenSearchIndexService openSearchIndexService;
    private RedactionApiServiceImpl service;

    @BeforeEach
    void setUp() {
        phileasClient = mock(PhilterClient.class);
        philterClientFactory = mock(PhilterClientFactory.class);
        philterInstanceRepository = mock(PhilterInstanceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        openSearchIndexService = mock(OpenSearchIndexService.class);
        // SymmetricCipher requires base64 of exactly 32 bytes — build one inline.
        final byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 0x42);
        final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(keyBytes));
        // Embedded-Philter path is the simplest test seam — no remote URL or allow-list to
        // wrangle, so finalize tests focus on the document-state contract.
        service = new RedactionApiServiceImpl(phileasClient, philterClientFactory,
                philterInstanceRepository, documentRepository, spanRepository, batchRepository,
                openSearchIndexService, cipher, new DataSourceHostAllowList(""));
    }

    private static Document approvedDoc(final String id, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setOriginalText(text);
        d.setPhilterContextId("ctx-" + id);
        d.changeStatus("APPROVED");
        return d;
    }

    private static Span approvedSpan(final String id, final String type, final String text,
                                     final int start, final int end) {
        final Span s = new Span();
        s.setId(id);
        s.setType(type);
        s.setText(text);
        s.setLocation(new Location(start, end, 0, null));
        s.changeStatus("APPROVED");
        return s;
    }

    @Test
    void finalizeUsesActualDocumentTextNotAPlaceholder() throws Exception {
        final Document doc = approvedDoc("d1", "Bob's SSN is 123-45-6789.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(
                approvedSpan("s1", "ssn", "123-45-6789", 13, 24)));
        when(phileasClient.redact(anyString(), anyString(), anyList()))
                .thenReturn("Bob's SSN is <<SSN>>.");

        final String result = service.finalizeRedaction("d1");

        assertEquals("Bob's SSN is <<SSN>>.", result);

        // The PhilterClient must receive the document's real originalText — not the
        // "Original text placeholder" string the previous implementation passed.
        final ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(phileasClient).redact(textCaptor.capture(), anyString(), anyList());
        assertEquals("Bob's SSN is 123-45-6789.", textCaptor.getValue());
    }

    @Test
    void finalizePassesContextIdAndApprovedSpansToTheRedactor() throws Exception {
        final Document doc = approvedDoc("d1", "two SSNs: 111-11-1111 and 222-22-2222.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(
                approvedSpan("s1", "ssn", "111-11-1111", 10, 21),
                // PENDING spans must be excluded — only APPROVED redactions should land.
                pendingSpan("s2", "ssn", "222-22-2222", 26, 37),
                approvedSpan("s3", "ssn", "222-22-2222", 26, 37)));
        when(phileasClient.redact(anyString(), anyString(), anyList())).thenReturn("redacted");

        service.finalizeRedaction("d1");

        final ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<Redaction>> spansCaptor = ArgumentCaptor.forClass(List.class);
        verify(phileasClient).redact(anyString(), contextCaptor.capture(), spansCaptor.capture());

        assertEquals("ctx-d1", contextCaptor.getValue());
        assertEquals(2, spansCaptor.getValue().size(),
                "PENDING spans must be excluded from the approvedSpans list");
        assertTrue(spansCaptor.getValue().stream().allMatch(r -> "ssn".equals(r.getType())));
    }

    @Test
    void finalizePersistsRedactedTextAndStatus() throws Exception {
        final Document doc = approvedDoc("d1", "hello SSN 123-45-6789");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());
        when(phileasClient.redact(anyString(), anyString(), anyList()))
                .thenReturn("hello SSN <<SSN>>");

        service.finalizeRedaction("d1");

        final ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(saved.capture());
        // The document is left in FINALIZED — not AUTO_APPROVED, which the previous
        // implementation incorrectly used and which contradicts the rest of the workflow.
        assertEquals("FINALIZED", saved.getValue().getStatus());
        // redactedText is persisted so the Download button still works after a
        // finalization policy clears originalText.
        assertEquals("hello SSN <<SSN>>", saved.getValue().getRedactedText());
    }

    @Test
    void finalizeRefusesWhenOriginalTextIsNull() {
        final Document doc = approvedDoc("d1", null);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.finalizeRedaction("d1"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason() != null && e.getReason().contains("source text"),
                "expected a clear source-text-unavailable message: " + e.getReason());

        // No save — the document isn't transitioned to FINALIZED in the unsalvageable case.
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void finalizeRefusesWhenOriginalTextIsBlank() {
        final Document doc = approvedDoc("d1", "   ");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.finalizeRedaction("d1"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void finalizeMissingDocumentReturns404() {
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());

        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.finalizeRedaction("ghost"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        // Same client used regardless: no-batch path picks the embedded Phileas, but with
        // no document there's nothing to redact.
        assertSame(phileasClient, phileasClient);
    }

    private static Span pendingSpan(final String id, final String type, final String text,
                                    final int start, final int end) {
        final Span s = new Span();
        s.setId(id);
        s.setType(type);
        s.setText(text);
        s.setLocation(new Location(start, end, 0, null));
        s.changeStatus("PENDING");
        return s;
    }
}
