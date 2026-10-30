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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The PII threshold, Document Threshold, and audit sampling rate a new batch starts with, and the
 * lowest audit sampling rate a batch may be given. Read from {@code philter-workspace.batch.*} (or
 * {@code PHILTER_WORKSPACE_BATCH_*}), so a bad value stops Philter Workspace at startup. Existing
 * batches keep the values they were saved with.
 */
@Component
public class BatchDefaults {

    static final double PII_THRESHOLD = 0.80;
    static final double DOCUMENT_THRESHOLD = 0.25;
    static final double AUDIT_SAMPLING_RATE = 0.10;

    private final double piiThreshold;
    private final double documentThreshold;
    private final double auditSamplingRate;
    private final double minimumAuditSamplingRate;

    public BatchDefaults(
            @Value("${philter-workspace.batch.defaults.pii-threshold:}") final String piiThreshold,
            @Value("${philter-workspace.batch.defaults.document-threshold:}") final String documentThreshold,
            @Value("${philter-workspace.batch.defaults.audit-sampling-rate:}") final String auditSamplingRate,
            @Value("${philter-workspace.batch.minimum-audit-sampling-rate:}") final String minimumAuditSamplingRate) {
        this.piiThreshold = fraction("philter-workspace.batch.defaults.pii-threshold", piiThreshold, PII_THRESHOLD);
        this.documentThreshold = fraction("philter-workspace.batch.defaults.document-threshold", documentThreshold,
                DOCUMENT_THRESHOLD);
        this.minimumAuditSamplingRate = fraction("philter-workspace.batch.minimum-audit-sampling-rate",
                minimumAuditSamplingRate, 0.0);
        // With no default given, new batches start at the minimum when it is above today's default.
        this.auditSamplingRate = fraction("philter-workspace.batch.defaults.audit-sampling-rate", auditSamplingRate,
                Math.max(AUDIT_SAMPLING_RATE, this.minimumAuditSamplingRate));
        if (this.auditSamplingRate < this.minimumAuditSamplingRate) {
            throw new IllegalStateException("philter-workspace.batch.defaults.audit-sampling-rate ("
                    + this.auditSamplingRate + ") is below philter-workspace.batch.minimum-audit-sampling-rate ("
                    + this.minimumAuditSamplingRate + "), so new batches could not be saved. Raise the default "
                    + "(PHILTER_WORKSPACE_BATCH_DEFAULTS_AUDIT_SAMPLING_RATE) or lower the minimum "
                    + "(PHILTER_WORKSPACE_BATCH_MINIMUM_AUDIT_SAMPLING_RATE).");
        }
    }

    /** The given defaults and minimum, validated as configured values are. For tests and callers without Spring. */
    public static BatchDefaults of(final double piiThreshold, final double documentThreshold,
                                   final double auditSamplingRate, final double minimumAuditSamplingRate) {
        return new BatchDefaults(String.valueOf(piiThreshold), String.valueOf(documentThreshold),
                String.valueOf(auditSamplingRate), String.valueOf(minimumAuditSamplingRate));
    }

    /** Today's defaults (0.80, 0.25, 0.10) and no minimum. */
    public static BatchDefaults standard() {
        return new BatchDefaults("", "", "", "");
    }

    /** Give a new batch the configured thresholds and audit sampling rate. */
    public Batch apply(final Batch batch) {
        batch.setConfidenceThreshold(piiThreshold);
        batch.setDocumentThreshold(documentThreshold);
        batch.setAuditSamplingRate(auditSamplingRate);
        return batch;
    }

    public double piiThreshold() {
        return piiThreshold;
    }

    public double documentThreshold() {
        return documentThreshold;
    }

    public double auditSamplingRate() {
        return auditSamplingRate;
    }

    /** The lowest audit sampling rate a batch may be given; 0 when no minimum is set. */
    public double minimumAuditSamplingRate() {
        return minimumAuditSamplingRate;
    }

    /** Whether {@code rate} is below the configured minimum. */
    public boolean belowMinimum(final double rate) {
        return rate < minimumAuditSamplingRate;
    }

    private static double fraction(final String property, final String value, final double otherwise) {
        if (value == null || value.isBlank()) {
            return otherwise;
        }
        final double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(property + " (" + envName(property) + ") must be a number from 0 to 1, not \""
                    + value.trim() + "\".");
        }
        if (Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0) {
            throw new IllegalStateException(property + " (" + envName(property) + ") must be from 0 to 1, not "
                    + value.trim() + ".");
        }
        return parsed;
    }

    private static String envName(final String property) {
        return property.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
