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
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.store.DocumentQueueStore;
import ai.philterd.workspace.service.RedactionService;
import ai.philterd.workspace.util.Hashing;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;

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
    private DocumentQueueStore queueStore;
    private GeneralSettingsService generalSettingsService;
    private final ai.philterd.workspace.model.GeneralSettings settings = new ai.philterd.workspace.model.GeneralSettings();
    private IngestQueueService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        pendingUploadRepository = mock(PendingUploadRepository.class);
        batchRepository = mock(BatchRepository.class);
        redactionService = mock(RedactionService.class);
        persistenceService = mock(RedactionPersistenceService.class);
        queueStore = mock(DocumentQueueStore.class);
        generalSettingsService = mock(GeneralSettingsService.class);
        when(generalSettingsService.load()).thenReturn(settings);
        service = new IngestQueueService(documentRepository, pendingUploadRepository,
                batchRepository, redactionService, persistenceService, queueStore, generalSettingsService);
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

    // ---------- the policy chosen by case type (#16) ----------

    private Document queued(final String caseType) {
        final Document d = new Document();
        d.setId("d1");
        d.setBatchId("b1");
        d.setOriginalText("Call Jane Roe.");
        d.setCaseMetadata(caseType == null ? null
                : ai.philterd.workspace.model.CaseMetadata.of("1:26-cv-01234", caseType, "Acme Dental LLC", null, null));
        return d;
    }

    private Batch byCaseType() {
        final Batch b = batch("b1");
        b.setPolicyName("batch-policy");
        b.setPolicyByCaseType(true);
        when(batchRepository.findById("b1")).thenReturn(java.util.Optional.of(b));
        settings.getCaseTypePolicies().add(new ai.philterd.workspace.model.CaseTypePolicy("civil", "civil-filings"));
        return b;
    }

    @Test
    void aBatchThatChoosesByCaseTypeRedactsWithTheMappedPolicy() throws Exception {
        byCaseType();
        when(redactionService.redactText(any(), any(), any())).thenReturn(
                new ai.philterd.workspace.core.model.RedactionResponse("Call Jane Roe.", "Call PERSON.", List.of()));

        service.processOne(queued("Civil"));

        verify(redactionService).redactText("Call Jane Roe.", "civil-filings", "");
    }

    @Test
    void aMissingOrUnmappedCaseTypeIsFlaggedAndNotRedacted() throws Exception {
        byCaseType();
        final Document unmapped = queued("criminal");
        service.processOne(unmapped);
        assertEquals("FAILED", unmapped.getStatus());
        assertEquals("The batch chooses its policy by case type, and no policy is mapped to case type \"criminal\". "
                + "Map it under Admin, General, Case types, then retry it.", unmapped.getFailureMessage(),
                "the reason alone, with no stack trace");

        final Document none = queued(null);
        service.processOne(none);
        assertEquals("FAILED", none.getStatus());
        assertTrue(none.getFailureMessage().contains("has no case type"));

        verify(redactionService, org.mockito.Mockito.never()).redactText(any(), any(), any());
        verify(persistenceService, org.mockito.Mockito.never()).apply(any(), any(), any());
        assertFalse(listAppender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Jane")
                || e.getFormattedMessage().contains("1:26-cv")), "no metadata or text in the log");
    }

    @Test
    void otherBatchesKeepTheirOwnPolicyWhateverTheCaseType() throws Exception {
        final Batch b = byCaseType();
        b.setPolicyByCaseType(false);
        when(redactionService.redactText(any(), any(), any())).thenReturn(
                new ai.philterd.workspace.core.model.RedactionResponse("Call Jane Roe.", "Call PERSON.", List.of()));
        service.processOne(queued("criminal"));
        verify(redactionService).redactText("Call Jane Roe.", "batch-policy", "");
    }

    @Test
    void caseMetadataIsStoredWithTheQueuedDocument() {
        final ai.philterd.workspace.model.CaseMetadata m =
                ai.philterd.workspace.model.CaseMetadata.of(null, "civil", null, "paid", true);
        assertEquals(m, service.enqueueText(batch("b1"), "doc.txt", "x", 2, m).getCaseMetadata());
        assertEquals(m, service.enqueueFile(batch("b1"), "doc.pdf", new byte[]{1}, MediaType.APPLICATION_PDF_VALUE, 2, m)
                .getCaseMetadata());
    }
}
