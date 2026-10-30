/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.store.DocumentQueueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Retrying failed documents from Admin, Ingest Queue (#59). */
class AdminIngestQueueRetryTest {

    private static final Authentication ADMIN = new UsernamePasswordAuthenticationToken("admin@example.com", null, List.of());

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final PendingUploadRepository uploads = mock(PendingUploadRepository.class);
    private final DocumentQueueStore queue = mock(DocumentQueueStore.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ai.philterd.workspace.service.OpenSearchIndexService index =
            mock(ai.philterd.workspace.service.OpenSearchIndexService.class);
    private AdminIngestQueueController controller;

    @BeforeEach
    void setUp() {
        when(audit.hashForAudit(anyString())).thenReturn("hash");
        controller = new AdminIngestQueueController(documents, mock(BatchRepository.class), uploads, queue, audit, index);
    }

    @Test
    void aFailedUploadIsQueuedAgainAndAudited() {
        final Document failed = failed("f1", "claim.txt", null);
        when(uploads.existsById("f1")).thenReturn(true);
        when(queue.requeueFailed("f1")).thenReturn(failed);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retry("f1", ADMIN, ra);

        assertEquals("Queued \"claim.txt\" to be redacted again.", ra.getFlashAttributes().get("success"));
        verify(queue).requeueFailed("f1");
        verify(audit).log(eq("INGEST_QUEUE_RETRY"), eq("Document"), eq("f1"), eq(Map.of("batchId", "b1",
                "filenameHash", "hash", "filenameLength", 9, "retriedBy", "admin@example.com")));
    }

    @Test
    void aFailedTextDocumentIsQueuedAgainFromItsText() {
        final Document text = failed("t1", "note", "Call Jane Roe.");
        when(queue.requeueFailed("t1")).thenReturn(text);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retry("t1", ADMIN, ra);

        assertEquals("Queued \"note\" to be redacted again.", ra.getFlashAttributes().get("success"));
    }

    @Test
    void aDocumentWithNothingLeftToRedactIsNotRetried() {
        failed("g1", "gone.pdf", null);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retry("g1", ADMIN, ra);

        assertEquals("\"gone.pdf\" cannot be retried: nothing of it is left to redact. Upload it again.",
                ra.getFlashAttributes().get("error"));
        verify(queue, never()).requeueFailed(any());
        verify(audit, never()).log(any(), any(), any(), any());
    }

    @Test
    void aDocumentNoLongerFailedIsNotRetried() {
        final Document done = failed("d1", "done.txt", "text");
        done.setStatus("REVIEW_REQUIRED");
        when(uploads.existsById("d1")).thenReturn(true);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retry("d1", ADMIN, ra);
        assertEquals("Document not found in the failures list (it may have been retried or removed).",
                ra.getFlashAttributes().get("error"));
        verify(queue, never()).requeueFailed(any());

        // Retried by someone else between the read and the requeue.
        failed("r1", "raced.txt", "text");
        final RedirectAttributes raced = new RedirectAttributesModelMap();
        controller.retry("r1", ADMIN, raced);
        assertEquals("Document not found in the failures list (it may have been retried or removed).",
                raced.getFlashAttributes().get("error"));
        verify(audit, never()).log(eq("INGEST_QUEUE_RETRY"), any(), any(), any());
    }

    @Test
    void retryAllQueuesWhatCanBeRedactedAndCountsTheRest() {
        final Document upload = failed("a", "a.pdf", null);
        final Document text = failed("b", "b", "text");
        final Document gone = failed("c", "c.pdf", null);
        when(uploads.existsById("a")).thenReturn(true);
        when(documents.findByStatus(eq("FAILED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(upload, text, gone)));
        when(queue.requeueFailed("a")).thenReturn(upload);
        when(queue.requeueFailed("b")).thenReturn(text);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retryAllFailures(ADMIN, ra);

        assertEquals("Queued 2 failed documents to be redacted again. 1 could not be retried, since nothing of it "
                + "is left to redact.", ra.getFlashAttributes().get("success"));
        verify(queue, never()).requeueFailed("c");
        verify(audit).log(eq("INGEST_QUEUE_RETRY_ALL"), eq("Settings"), eq("ingest-queue"),
                eq(Map.of("count", 2L, "skipped", 1L, "retriedBy", "admin@example.com")));
    }

    @Test
    void retryAllWithNothingFailedSaysSo() {
        when(documents.findByStatus(eq("FAILED"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.retryAllFailures(ADMIN, ra);
        assertEquals("No failed documents to retry.", ra.getFlashAttributes().get("error"));
    }

    private Document failed(final String id, final String filename, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId("b1");
        d.setFilename(filename);
        d.setStatus("FAILED");
        d.setOriginalText(text);
        when(documents.findById(id)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void removingAQueuedOrFailedDocumentRemovesItFromTheSearchIndex() {
        final Document pending = new Document();
        pending.setId("p1");
        pending.setFilename("queued.txt");
        when(queue.removeIfStatus("p1", "PENDING")).thenReturn(pending);
        controller.remove("p1", ADMIN, new RedirectAttributesModelMap());
        verify(index).removeDocument("p1");

        final Document failed = new Document();
        failed.setId("f1");
        failed.setFilename("failed.txt");
        when(queue.removeIfStatus("f1", "FAILED")).thenReturn(failed);
        controller.clearFailure("f1", ADMIN, new RedirectAttributesModelMap());
        verify(index).removeDocument("f1");

        when(queue.removeAllWithStatus("FAILED")).thenReturn(List.of("f2", "f3"));
        controller.clearAllFailures(ADMIN, new RedirectAttributesModelMap());
        verify(index).removeDocument("f2");
        verify(index).removeDocument("f3");
    }
}
