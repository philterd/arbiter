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

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PendingUploadRepository;
import ai.philterd.arbiter.service.RedactionService;
import ai.philterd.arbiter.util.Hashing;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the SHA-512 content hash is recorded on every document the queue ingests,
 * for both the text and binary upload paths.
 */
class IngestQueueServiceTest {

    private DocumentRepository documentRepository;
    private PendingUploadRepository pendingUploadRepository;
    private BatchRepository batchRepository;
    private RedactionService redactionService;
    private RedactionPersistenceService persistenceService;
    private MongoOperations mongoOperations;
    private IngestQueueService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        pendingUploadRepository = mock(PendingUploadRepository.class);
        batchRepository = mock(BatchRepository.class);
        redactionService = mock(RedactionService.class);
        persistenceService = mock(RedactionPersistenceService.class);
        mongoOperations = mock(MongoOperations.class);
        service = new IngestQueueService(documentRepository, pendingUploadRepository,
                batchRepository, redactionService, persistenceService, mongoOperations);
    }

    private static Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        return b;
    }

    @Test
    void enqueueTextHashesUtf8BytesOfText() {
        final Document saved = service.enqueueText(batch("b1"), "doc.txt", "hello world", 2);

        assertEquals("PENDING", saved.getStatus());
        assertEquals("hello world", saved.getOriginalText());
        assertEquals(Hashing.sha512Hex("hello world"), saved.getContentSha512());

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals(Hashing.sha512Hex("hello world"), captor.getValue().getContentSha512());
    }

    @Test
    void enqueueTextHandlesNullTextAsEmptyString() {
        final Document saved = service.enqueueText(batch("b1"), "empty.txt", null, 2);

        // Null is normalized to empty string so the document still has a deterministic hash.
        assertEquals("", saved.getOriginalText());
        assertEquals(Hashing.sha512Hex(""), saved.getContentSha512());
    }

    @Test
    void enqueueFileHashesRawBytes() {
        final byte[] bytes = new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D };  // "%PDF-"
        final Document saved = service.enqueueFile(batch("b1"), "file.pdf", bytes,
                MediaType.APPLICATION_PDF_VALUE, 2);

        assertNotNull(saved.getId());
        assertEquals("PENDING", saved.getStatus());
        // For binary uploads the hash is over the raw bytes, NOT a UTF-8 decoding.
        assertEquals(Hashing.sha512Hex(bytes), saved.getContentSha512());

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals(Hashing.sha512Hex(bytes), captor.getValue().getContentSha512());
    }

    @Test
    void enqueueFileHandlesNullBytesAsEmpty() {
        final Document saved = service.enqueueFile(batch("b1"), "empty.bin", null, "application/octet-stream", 2);

        assertEquals(Hashing.sha512Hex(new byte[0]), saved.getContentSha512());
    }

    // ---------- filename never appears in the redaction-failure log (finding #6) ----------

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logbackLogger;

    @BeforeEach
    void attachLogAppender() {
        // Capture log events from IngestQueueService so we can inspect what's
        // actually written. Logback's ListAppender stores events in-memory.
        logbackLogger = (Logger) LoggerFactory.getLogger(IngestQueueService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logbackLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (logbackLogger != null && listAppender != null) {
            logbackLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    void redactionFailureLogDoesNotEchoFilename() throws Exception {
        // Filenames in this product routinely carry PII (medical records named
        // for the patient, tax returns named for the filer). Logging the
        // filename on the redaction-failure path would leak that PII to the
        // application log — typically read by operators who don't have
        // document-level read access.
        final Document doc = new Document();
        doc.setId("doc-42");
        doc.setBatchId("b1");
        doc.setFilename("mrn-12345678-jane-doe-discharge.pdf");
        doc.setOriginalText("ignored — redaction will throw before we use it");

        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));
        when(pendingUploadRepository.findById("doc-42")).thenReturn(Optional.empty());
        // Force the redact path to throw so we hit the catch branch under test.
        when(redactionService.redactText(anyString(), any(), any()))
                .thenThrow(new RuntimeException("upstream Philter timed out"));

        service.processOne(doc);

        // Find the warn-level event from the catch block.
        ILoggingEvent failureEvent = null;
        for (ILoggingEvent e : listAppender.list) {
            if (e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("redaction failed")) {
                failureEvent = e;
                break;
            }
        }
        assertNotNull(failureEvent, "expected a WARN log line on the failure path");

        final String rendered = failureEvent.getFormattedMessage();
        assertTrue(rendered.contains("doc-42"),
                "the document id should still appear — that's the correlation key");
        assertFalse(rendered.contains("mrn-12345678-jane-doe-discharge.pdf"),
                "filename must NOT appear in the log message — would leak PII: " + rendered);
        // Belt-and-braces: not in any part of the MDC / structured arguments either.
        final Object[] args = failureEvent.getArgumentArray();
        if (args != null) {
            for (Object a : args) {
                if (a != null) {
                    assertFalse(a.toString().contains("mrn-12345678-jane-doe-discharge.pdf"),
                            "filename leaked via a log argument: " + a);
                }
            }
        }
        // The exception class name and message are fine to log — they don't echo
        // the filename and they're necessary for operator diagnostics.
        assertTrue(rendered.contains("RuntimeException")
                        || rendered.contains("upstream Philter timed out"),
                "the cause should still be diagnosable from the log: " + rendered);
    }
}
