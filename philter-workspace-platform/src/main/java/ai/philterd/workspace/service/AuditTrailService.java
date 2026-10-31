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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One audit trail (#18): Philter Workspace's audit events and the entries of Philter's ledger for the same documents,
 * in time order, with how each ledger chain verified. Philter Workspace's events are as its audit log stores them,
 * and the ledger entries carry no original value, replacement, or filename (see {@link DocumentLedgerService}), so
 * the trail holds no personal information the audit log does not already hold.
 */
@Service
public class AuditTrailService {

    /** The most Philter Workspace events in one trail, as for the audit log's own export. */
    public static final int MAX_EVENTS = 10_000;
    /** The most documents whose ledger a date-range trail reads, one Philter call or more each. */
    public static final int MAX_DOCUMENTS = 500;

    public static final String SOURCE_WORKSPACE = "PHILTER_WORKSPACE";
    public static final String SOURCE_LEDGER = "PHILTER_LEDGER";

    private final AuditLogQueryService auditLogQueryService;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final DocumentLedgerService ledgerService;

    public AuditTrailService(final AuditLogQueryService auditLogQueryService,
                             final DocumentRepository documentRepository, final SpanRepository spanRepository,
                             final DocumentLedgerService ledgerService) {
        this.auditLogQueryService = auditLogQueryService;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.ledgerService = ledgerService;
    }

    /** A Philter Workspace event or a ledger entry, and the Philter Workspace document it belongs to. */
    public record Event(Instant timestamp, String source, String documentId, AuditLog workspace,
                        DocumentLedgerService.Entry ledger) {
    }

    /** A ledger chain and the Philter Workspace document it belongs to. */
    public record DocumentChain(String documentId, DocumentLedgerService.Chain chain) {
    }

    /**
     * The trail. {@code documentsTruncated} is set when a date range touched more documents than
     * {@link #MAX_DOCUMENTS}, so some ledgers were not read; {@code eventsTruncated} when Philter Workspace's events
     * reached {@link #MAX_EVENTS}.
     */
    public record Trail(List<Event> events, List<DocumentChain> chains, boolean eventsTruncated,
                        boolean documentsTruncated) {

        public long verifiedChains() {
            return chains.stream().filter(c -> DocumentLedgerService.STATE_OK.equals(c.chain().state())
                    && Boolean.TRUE.equals(c.chain().valid())).count();
        }
    }

    /** One document's trail: every event about it or its spans, and every entry of its ledger chains. */
    public Trail forDocument(final Document document) {
        final List<Span> spans = spanRepository.findByDocumentId(document.getId());
        final List<AuditLog> logged = auditLogQueryService.findForDocument(document.getId(),
                spans.stream().map(Span::getId).toList());
        final List<Event> events = new ArrayList<>();
        for (AuditLog log : logged) {
            events.add(new Event(log.getTimestamp(), SOURCE_WORKSPACE, document.getId(), log, null));
        }
        final List<DocumentChain> chains = new ArrayList<>();
        addLedger(document, null, null, events, chains);
        events.sort(ORDER);
        return new Trail(events, chains, false, false);
    }

    /**
     * Every Philter Workspace event in {@code [start, end]}, and the ledger entries in that range of the documents
     * those events concern. Either bound may be null.
     */
    public Trail forRange(final Instant start, final Instant end) {
        final List<AuditLog> logged = auditLogQueryService.find(start, end, null, null, null, MAX_EVENTS);
        final Map<String, String> documentOfSpan = documentsOfSpans(logged);
        final List<Event> events = new ArrayList<>();
        final Set<String> documentIds = new LinkedHashSet<>();
        for (AuditLog log : logged) {
            final String documentId = documentIdOf(log, documentOfSpan);
            if (documentId != null) documentIds.add(documentId);
            events.add(new Event(log.getTimestamp(), SOURCE_WORKSPACE, documentId, log, null));
        }
        final List<DocumentChain> chains = new ArrayList<>();
        int read = 0;
        for (String documentId : documentIds) {
            if (read == MAX_DOCUMENTS) break;
            final Document document = documentRepository.findById(documentId).orElse(null);
            if (document == null) continue;
            read++;
            addLedger(document, start, end, events, chains);
        }
        events.sort(ORDER);
        return new Trail(events, chains, logged.size() >= MAX_EVENTS, documentIds.size() > MAX_DOCUMENTS);
    }

    private void addLedger(final Document document, final Instant start, final Instant end, final List<Event> events,
                           final List<DocumentChain> chains) {
        final DocumentLedgerService.Ledger ledger = ledgerService.forDocument(document);
        for (DocumentLedgerService.Chain chain : ledger.chains()) {
            chains.add(new DocumentChain(document.getId(), chain));
        }
        for (DocumentLedgerService.Entry entry : ledger.entries()) {
            final Instant at = entry.timestamp();
            if (at != null && ((start != null && at.isBefore(start)) || (end != null && at.isAfter(end)))) continue;
            events.add(new Event(at, SOURCE_LEDGER, document.getId(), null, entry));
        }
    }

    private Map<String, String> documentsOfSpans(final List<AuditLog> logged) {
        final Set<String> spanIds = new LinkedHashSet<>();
        for (AuditLog log : logged) {
            if ("Span".equals(log.getResourceType()) && log.getResourceId() != null) spanIds.add(log.getResourceId());
        }
        final Map<String, String> out = new HashMap<>();
        if (!spanIds.isEmpty()) {
            for (Span span : spanRepository.findAllById(spanIds)) {
                out.put(span.getId(), span.getDocumentId());
            }
        }
        return out;
    }

    private static String documentIdOf(final AuditLog log, final Map<String, String> documentOfSpan) {
        if ("Document".equals(log.getResourceType())) return log.getResourceId();
        if ("Span".equals(log.getResourceType())) return documentOfSpan.get(log.getResourceId());
        return null;
    }

    /**
     * Oldest first. Within a chain, entries keep their chain order (the sort is stable, and they were added in
     * order); an entry with no readable timestamp sorts last.
     */
    private static final Comparator<Event> ORDER = Comparator.comparing(Event::timestamp,
            Comparator.nullsLast(Comparator.naturalOrder()));
}
