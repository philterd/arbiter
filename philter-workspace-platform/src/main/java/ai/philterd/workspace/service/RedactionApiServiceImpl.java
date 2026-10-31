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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.IngestStatus;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.philter.PhilterClient;
import ai.philterd.workspace.philter.PhilterClientFactory;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedactionApiServiceImpl implements RedactionApiService {


    private final PhilterClientFactory philterClientFactory;
    private final PhilterSettings philter;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final DocumentSearchService searchService;

    public RedactionApiServiceImpl(final PhilterClientFactory philterClientFactory,
                                   final PhilterSettings philter,
                                   final DocumentRepository documentRepository,
                                   final SpanRepository spanRepository,
                                   final BatchRepository batchRepository,
                                   final DocumentSearchService searchService) {
        this.philterClientFactory = philterClientFactory;
        this.philter = philter;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.searchService = searchService;
    }

    private PhilterClient philterClient(final Batch batch) {
        return philterClientFactory.create(philter.url(), philter.apiKey(),
                batch == null ? null : batch.getPolicyName());
    }

    @Override
    public void processDocument(final String documentId, final String text) throws IOException {
        final Document document = documentRepository.findById(documentId).orElseThrow();
        final Batch batch = batchRepository.findById(document.getBatchId() == null ? "" : document.getBatchId()).orElse(null);
        final double threshold = batch == null ? 0.8 : batch.getConfidenceThreshold();

        // Use the batch's configured context if set, otherwise fall back to a unique id so that
        // finalize-time redaction can replay the exact context.
        final String batchContext = batch == null ? null : batch.getContext();
        final String contextId = (batchContext != null && !batchContext.isEmpty())
                ? batchContext
                : UUID.randomUUID().toString();
        final Map<String, Object> explanation = philterClient(batch).explain(text, contextId);

        final List<Map<String, Object>> explanationSpans = (List<Map<String, Object>>) explanation.get("explanation");

        final List<Span> persistedSpans = new ArrayList<>();
        if (explanationSpans != null) {
            for (Map<String, Object> exp : explanationSpans) {
                final Span span = new Span();
                span.setDocumentId(documentId);
                span.setText((String) exp.get("text"));
                span.setType((String) exp.get("type"));
                final double confidence = (Double) exp.getOrDefault("confidence", 0.0);
                span.setConfidence(confidence);
                span.setCreatedAt(java.time.LocalDateTime.now());
                span.changeStatus(confidence >= threshold ? "APPROVED" : "PENDING");

                final int start = (Integer) exp.get("characterStart");
                final int end = (Integer) exp.get("characterEnd");
                final int page = (Integer) exp.getOrDefault("page", 0);

                final Location location = new Location(start, end, page, null);
                span.setLocation(location);

                persistedSpans.add(spanRepository.save(span));
            }
        }

        final boolean needsReview = !persistedSpans.isEmpty()
                && persistedSpans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.setPhilterContextId(contextId);
        document.changeStatus(IngestStatus.pick(batch, needsReview));
        documentRepository.save(document);
        if (document.getOriginalText() == null) {
            document.setOriginalText(text);
        }
        searchService.indexDocument(document);
    }

    // TODO: Reach parity with ReviewViewController.finalizeDocument by also generating a
    // Redaction Certificate, writing a DOCUMENT_FINALIZE audit entry, and applying the
    // batch's finalization policy (legal hold / delete-immediately). Today the API
    // finalize path leaves the document in FINALIZED but skips all three side-effects, so a
    // document finalized via the API has no certificate and no auditable trail of who
    // finalized it. Best fix: extract a DocumentFinalizationService that both this method
    // and ReviewViewController.finalizeDocument call into so the two paths can't drift.
    @Override
    public String finalizeRedaction(final String documentId) throws IOException {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."));

        // The redactor needs the actual document content. If a prior finalization-policy run
        // wiped originalText, we cannot reproduce the redacted output and must refuse rather
        // than emit a redaction of empty/placeholder text.
        final String originalText = document.getOriginalText();
        if (originalText == null || originalText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document source text is unavailable; it may have been cleared by a "
                            + "finalization policy and cannot be re-finalized.");
        }

        final Batch batch = batchRepository.findById(document.getBatchId() == null ? "" : document.getBatchId()).orElse(null);
        final List<Span> spans = spanRepository.findByDocumentId(documentId);

        final List<ai.philterd.workspace.core.model.Redaction> approvedSpans = spans.stream()
                .filter(s -> "APPROVED".equals(s.getStatus()))
                .map(s -> {
                    final ai.philterd.workspace.core.model.Redaction r = new ai.philterd.workspace.core.model.Redaction();
                    r.setText(s.getText());
                    r.setType(s.getType());
                    r.setStart(s.getLocation().characterStart());
                    r.setEnd(s.getLocation().characterEnd());
                    return r;
                })
                .collect(Collectors.toList());

        final String finalizedText = philterClient(batch).redact(originalText,
                document.getPhilterContextId(), approvedSpans);

        // Persist the rendered text on the document so the Download button keeps working
        // after a finalization policy clears originalText, and so the API and UI finalize
        // paths leave the document in the same state.
        document.setRedactedText(finalizedText);
        document.changeStatus("FINALIZED");
        documentRepository.save(document);

        return finalizedText;
    }

}
