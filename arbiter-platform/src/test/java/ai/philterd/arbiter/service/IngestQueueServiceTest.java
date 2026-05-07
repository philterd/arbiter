/*
 * Copyright 2026 Philterd
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
