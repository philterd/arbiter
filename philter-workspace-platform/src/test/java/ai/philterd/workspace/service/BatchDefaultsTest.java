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

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Defaults for new batches and the minimum audit sampling rate, from configuration (#37). */
class BatchDefaultsTest {

    @Test
    void withNothingConfiguredNewBatchesGetTodaysDefaults() {
        final Batch batch = BatchDefaults.standard().apply(new Batch());
        assertEquals(0.80, batch.getConfidenceThreshold());
        assertEquals(0.25, batch.getDocumentThreshold());
        assertEquals(0.10, batch.getAuditSamplingRate());
        assertEquals(0.0, BatchDefaults.standard().minimumAuditSamplingRate());
        // The model's own defaults, which a stored batch missing a field reads as, are the same.
        final Batch plain = new Batch();
        assertEquals(0.80, plain.getConfidenceThreshold());
        assertEquals(0.25, plain.getDocumentThreshold());
        assertEquals(0.10, plain.getAuditSamplingRate());
    }

    @Test
    void configuredValuesAreApplied() {
        final Batch batch = new BatchDefaults(" 0.9 ", "0.3", "1", "").apply(new Batch());
        assertEquals(0.9, batch.getConfidenceThreshold());
        assertEquals(0.3, batch.getDocumentThreshold());
        assertEquals(1.0, batch.getAuditSamplingRate());
    }

    @Test
    void aMinimumAloneRaisesTheDefaultRateToIt() {
        final BatchDefaults defaults = new BatchDefaults("", "", "", "1.0");
        assertEquals(1.0, defaults.auditSamplingRate());
        assertTrue(defaults.belowMinimum(0.99));
        assertEquals(0.10, new BatchDefaults("", "", "", "0.05").auditSamplingRate());
    }

    @Test
    void theEnvironmentVariablesInTheDocsSetTheProperties() {
        // As Spring Boot resolves @Value: environment variables, with configuration-property name mapping.
        final org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "env", java.util.Map.of(
                        "PHILTER_WORKSPACE_BATCH_DEFAULTS_PII_THRESHOLD", "0.9",
                        "PHILTER_WORKSPACE_BATCH_DEFAULTS_DOCUMENT_THRESHOLD", "0.3",
                        "PHILTER_WORKSPACE_BATCH_DEFAULTS_AUDIT_SAMPLING_RATE", "1",
                        "PHILTER_WORKSPACE_BATCH_MINIMUM_AUDIT_SAMPLING_RATE", "1")));
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(environment);

        final BatchDefaults defaults = new BatchDefaults(
                environment.resolvePlaceholders("${philter-workspace.batch.defaults.pii-threshold:}"),
                environment.resolvePlaceholders("${philter-workspace.batch.defaults.document-threshold:}"),
                environment.resolvePlaceholders("${philter-workspace.batch.defaults.audit-sampling-rate:}"),
                environment.resolvePlaceholders("${philter-workspace.batch.minimum-audit-sampling-rate:}"));
        assertEquals(0.9, defaults.piiThreshold());
        assertEquals(0.3, defaults.documentThreshold());
        assertEquals(1.0, defaults.auditSamplingRate());
        assertEquals(1.0, defaults.minimumAuditSamplingRate());
    }

    @Test
    void invalidValuesStopStartupWithAClearMessage() {
        assertEquals("philter-workspace.batch.defaults.pii-threshold (PHILTER_WORKSPACE_BATCH_DEFAULTS_PII_THRESHOLD) "
                        + "must be from 0 to 1, not 1.5.",
                assertThrows(IllegalStateException.class, () -> new BatchDefaults("1.5", "", "", "")).getMessage());
        assertEquals("philter-workspace.batch.defaults.document-threshold "
                        + "(PHILTER_WORKSPACE_BATCH_DEFAULTS_DOCUMENT_THRESHOLD) must be a number from 0 to 1, not \"high\".",
                assertThrows(IllegalStateException.class, () -> new BatchDefaults("", "high", "", "")).getMessage());
        assertThrows(IllegalStateException.class, () -> new BatchDefaults("", "", "-0.1", ""));
        assertThrows(IllegalStateException.class, () -> new BatchDefaults("", "", "", "NaN"));

        final IllegalStateException belowMinimum = assertThrows(IllegalStateException.class,
                () -> new BatchDefaults("", "", "0.5", "1.0"));
        assertTrue(belowMinimum.getMessage().contains("PHILTER_WORKSPACE_BATCH_MINIMUM_AUDIT_SAMPLING_RATE"),
                belowMinimum.getMessage());
    }

    @Test
    void atAnAuditRateOfOneADocumentWithEveryDetectionAcceptedStillGoesToAPerson() {
        final RedactionPersistenceService ingest = new RedactionPersistenceService(mock(DocumentRepository.class),
                mock(SpanRepository.class), mock(OpenSearchIndexService.class));
        final String text = "Call Jane Roe at 555-0100.";
        final RedactionResponse response = new RedactionResponse(text, text, List.of(
                new Redaction("r1", "Jane Roe", 5, 13, "person", 0.97),
                new Redaction("r2", "555-0100", 17, 25, "phone-number", 0.99)));

        final Batch required = new BatchDefaults("", "", "1.0", "").apply(new Batch());
        for (int i = 0; i < 200; i++) {
            assertEquals("AUDIT_REQUIRED", ingest.apply(document(), required, response).getStatus());
        }

        // At today's default some of the same documents are approved without a person.
        final Batch standard = BatchDefaults.standard().apply(new Batch());
        boolean autoApproved = false;
        for (int i = 0; i < 500 && !autoApproved; i++) {
            autoApproved = "AUTO_APPROVED".equals(ingest.apply(document(), standard, response).getStatus());
        }
        assertTrue(autoApproved);
    }

    private static Document document() {
        final Document d = new Document();
        d.setId(UUID.randomUUID().toString());
        d.setBatchId("b1");
        return d;
    }
}
