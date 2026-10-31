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

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One audit trail of Philter Workspace's events and Philter's ledger (#18). */
class AuditTrailServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");

    private final AuditLogQueryService audit = mock(AuditLogQueryService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final DocumentLedgerService ledgers = mock(DocumentLedgerService.class);
    private AuditTrailService service;

    @BeforeEach
    void setUp() {
        service = new AuditTrailService(audit, documents, spans, ledgers);
    }

    private static AuditLog log(final String action, final String type, final String id, final int seconds) {
        final AuditLog l = new AuditLog();
        l.setAction(action);
        l.setResourceType(type);
        l.setResourceId(id);
        l.setTimestamp(T0.plusSeconds(seconds));
        return l;
    }

    private static DocumentLedgerService.Entry entry(final String chain, final Integer seconds, final long position) {
        return new DocumentLedgerService.Entry(chain, seconds == null ? null : T0.plusSeconds(seconds), "ssn",
                position, "h" + position, null, "dh", null, "court", 1, "ph", "k1", true, null);
    }

    private static DocumentLedgerService.Chain verified(final String chain) {
        return new DocumentLedgerService.Chain(chain, "OK", true, true, true, 2, 0, null, 2, false);
    }

    private static Document document(final String id) {
        final Document d = new Document();
        d.setId(id);
        return d;
    }

    private static Span span(final String id, final String documentId) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(documentId);
        return s;
    }

    @Test
    void aDocumentsEventsAndLedgerEntriesAreMergedOldestFirst() {
        final Document d = document("d1");
        when(spans.findByDocumentId("d1")).thenReturn(List.of(span("s1", "d1")));
        // The audit log returns newest first.
        when(audit.findForDocument(eq("d1"), eq(List.of("s1")))).thenReturn(List.of(
                log("DOCUMENT_FINALIZE", "Document", "d1", 30), log("SPAN_UPDATE", "Span", "s1", 20),
                log("DOCUMENT_QUEUED", "Document", "d1", 0)));
        when(ledgers.forDocument(d)).thenReturn(new DocumentLedgerService.Ledger(List.of(verified("p1")),
                List.of(entry("p1", 10, 4), entry("p1", 10, 9), entry("p1", null, 12))));

        final AuditTrailService.Trail trail = service.forDocument(d);

        assertEquals(List.of("DOCUMENT_QUEUED", "ledger:4", "ledger:9", "SPAN_UPDATE", "DOCUMENT_FINALIZE", "ledger:12"),
                trail.events().stream().map(e -> e.workspace() != null ? e.workspace().getAction()
                        : "ledger:" + e.ledger().startPosition()).toList(),
                "time order, chain order kept for equal times, an undated entry last");
        assertTrue(trail.events().stream().allMatch(e -> "d1".equals(e.documentId())));
        assertEquals(1, trail.verifiedChains());
        assertFalse(trail.eventsTruncated() || trail.documentsTruncated());
    }

    @Test
    void aRangeReadsTheLedgersOfTheDocumentsItsEventsConcernAndKeepsOnlyEntriesInTheRange() {
        final Instant start = T0.plusSeconds(5);
        final Instant end = T0.plusSeconds(50);
        when(audit.find(start, end, null, null, null, AuditTrailService.MAX_EVENTS)).thenReturn(List.of(
                log("SPAN_UPDATE", "Span", "s1", 20), log("DOCUMENT_APPROVAL", "Document", "d2", 30),
                log("USER_UPDATE", "User", "u1", 40)));
        when(spans.findAllById(any())).thenReturn(List.of(span("s1", "d1")));
        when(documents.findById("d1")).thenReturn(Optional.of(document("d1")));
        when(documents.findById("d2")).thenReturn(Optional.of(document("d2")));
        when(ledgers.forDocument(any())).thenAnswer(inv -> {
            final String id = ((Document) inv.getArgument(0)).getId();
            return new DocumentLedgerService.Ledger(List.of(verified("p-" + id)),
                    List.of(entry("p-" + id, 1, 1), entry("p-" + id, 25, 2), entry("p-" + id, 60, 3)));
        });

        final AuditTrailService.Trail trail = service.forRange(start, end);

        assertEquals(List.of("SPAN_UPDATE:d1", "p-d1:2", "p-d2:2", "DOCUMENT_APPROVAL:d2", "USER_UPDATE:null"),
                trail.events().stream().map(e -> e.workspace() != null
                        ? e.workspace().getAction() + ":" + e.documentId()
                        : e.ledger().philterDocumentId() + ":" + e.ledger().startPosition()).toList(),
                "a span's event belongs to its document; entries outside the range are left out");
        assertEquals(List.of("d1", "d2"), trail.chains().stream().map(AuditTrailService.DocumentChain::documentId)
                .toList());
        assertEquals(2, trail.verifiedChains());
    }

    @Test
    void aRangeReadsAtMostTheCapOfLedgersAndSaysSo() {
        final List<AuditLog> logged = new ArrayList<>();
        for (int i = 0; i <= AuditTrailService.MAX_DOCUMENTS; i++) {
            logged.add(log("DOCUMENT_QUEUED", "Document", "d" + i, i));
            when(documents.findById("d" + i)).thenReturn(Optional.of(document("d" + i)));
        }
        when(audit.find(any(), any(), any(), any(), any(), eq(AuditTrailService.MAX_EVENTS))).thenReturn(logged);
        when(ledgers.forDocument(any())).thenReturn(new DocumentLedgerService.Ledger(List.of(), List.of()));

        final AuditTrailService.Trail trail = service.forRange(null, null);

        verify(ledgers, times(AuditTrailService.MAX_DOCUMENTS)).forDocument(any());
        assertTrue(trail.documentsTruncated());
        assertEquals(AuditTrailService.MAX_DOCUMENTS + 1, trail.events().size(), "every event is still there");
    }

    @Test
    void aRemovedDocumentsEventsStayWithoutALedger() {
        when(audit.find(any(), any(), any(), any(), any(), eq(AuditTrailService.MAX_EVENTS)))
                .thenReturn(List.of(log("INGEST_QUEUE_REMOVE", "Document", "gone", 1)));
        when(documents.findById(anyString())).thenReturn(Optional.empty());

        final AuditTrailService.Trail trail = service.forRange(null, null);
        assertEquals(1, trail.events().size());
        assertTrue(trail.chains().isEmpty());
    }
}
