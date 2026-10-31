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
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.philter.ApprovedSpans;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RedactionApiServiceImpl implements RedactionApiService {

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final DocumentDeliveryService deliveryService;

    public RedactionApiServiceImpl(final DocumentRepository documentRepository,
                                   final SpanRepository spanRepository,
                                   final BatchRepository batchRepository,
                                   final DocumentDeliveryService deliveryService) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.deliveryService = deliveryService;
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

        // The reviewers have decided what to redact, so their spans are applied here rather than asking Philter
        // to detect again.
        final String finalizedText = ApprovedSpans.apply(originalText, approvedSpans);

        // Persist the rendered text on the document so the Download button keeps working
        // after a finalization policy clears originalText, and so the API and UI finalize
        // paths leave the document in the same state.
        document.setRedactedText(finalizedText);
        document.changeStatus("FINALIZED");
        // Deliver the finalized output to the batch's destination, if it has one (#24).
        final org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        deliveryService.queue(document, batch, auth == null ? null : auth.getName());
        documentRepository.save(document);

        return finalizedText;
    }

}
