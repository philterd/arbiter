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

import ai.philterd.arbiter.model.BackgroundJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Focused test for {@link BatchExportService#recordFailureSample} (R2-F9).
 *
 * <p>Jackson's {@code JsonProcessingException} message typically quotes the
 * offending field value in the failing JSON — for an export of a PII-bearing
 * document, that's raw PII landing in {@code BackgroundJob.failureMessages},
 * which the Jobs page renders to operators with no encryption. The fix
 * records only the document id and the exception class.
 */
class BatchExportServiceFailureSampleTest {

    private BatchExportService newService() {
        // None of the dependencies are touched by recordFailureSample; all-mocks is fine.
        return new BatchExportService(
                mock(ai.philterd.arbiter.repository.BatchRepository.class),
                mock(ai.philterd.arbiter.repository.DocumentRepository.class),
                mock(ai.philterd.arbiter.repository.SpanRepository.class),
                mock(ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository.class),
                mock(ai.philterd.arbiter.repository.S3DestinationRepository.class),
                mock(JsonlExportRenderer.class),
                mock(BioExportRenderer.class),
                mock(PheyeJsonlExportRenderer.class),
                mock(DestinationWriter.class),
                mock(AuditLogService.class),
                mock(ai.philterd.arbiter.repository.BackgroundJobRepository.class));
    }

    @Test
    void recordsOnlyDocumentIdAndExceptionClass() {
        final BatchExportService svc = newService();
        final BackgroundJob job = new BackgroundJob();

        // Build an exception whose MESSAGE carries something that looks like PII
        // (this mirrors what Jackson would emit on a value-conversion error
        // against a span text or filename containing an SSN).
        final RuntimeException e = new RuntimeException(
                "Failed to render span text=\"555-12-3456 — John Doe — MRN 12345678\"");

        svc.recordFailureSample(job, "doc-42", e);

        assertEquals(1, job.getFailureMessages().size());
        final String recorded = job.getFailureMessages().get(0);
        assertEquals("Document doc-42: RuntimeException", recorded,
                "must record only the document id and exception class, not the message text");

        // Belt-and-braces: the recorded sample must not contain any byte of the
        // exception message — that's the leak vector.
        assertFalse(recorded.contains("555-12-3456"),
                "PII from the exception message leaked into the failure sample: " + recorded);
        assertFalse(recorded.contains("John Doe"));
        assertFalse(recorded.contains("MRN"));
    }

    @Test
    void recordsJsonProcessingExceptionByClassNameOnly() {
        // The canonical scenario: a Jackson value-conversion error during export.
        // The message would include the failing JSON path and offending value.
        final BatchExportService svc = newService();
        final BackgroundJob job = new BackgroundJob();

        final JsonProcessingException e = new JsonProcessingException(
                "Document field 'body' contains invalid value: \"555-12-3456\"") {};

        svc.recordFailureSample(job, "doc-1", e);

        final String recorded = job.getFailureMessages().get(0);
        assertTrue(recorded.startsWith("Document doc-1: "));
        assertTrue(recorded.endsWith("JsonProcessingException")
                        || recorded.endsWith(e.getClass().getSimpleName()),
                "expected class-name suffix, got: " + recorded);
        assertFalse(recorded.contains("555-12-3456"),
                "Jackson exception message must not be persisted: " + recorded);
    }

    @Test
    void stopsRecordingAtMaxFailureMessages() {
        // Regression guard for the existing cap. The R2-F9 fix changed the
        // payload format, not the cap behaviour, so this should still hold.
        final BatchExportService svc = newService();
        final BackgroundJob job = new BackgroundJob();
        for (int i = 0; i < BackgroundJob.MAX_FAILURE_MESSAGES + 5; i++) {
            svc.recordFailureSample(job, "doc-" + i, new RuntimeException("ignored"));
        }
        assertEquals(BackgroundJob.MAX_FAILURE_MESSAGES, job.getFailureMessages().size(),
                "must cap at MAX_FAILURE_MESSAGES to bound the document size");
    }

    @Test
    void initializesFailureMessagesListIfNull() {
        // Legacy job rows may have a null failureMessages list — the method
        // must not NPE.
        final BatchExportService svc = newService();
        final BackgroundJob job = new BackgroundJob();
        job.setFailureMessages(null);

        svc.recordFailureSample(job, "doc-1", new RuntimeException("anything"));

        assertEquals(1, job.getFailureMessages().size());
    }
}
