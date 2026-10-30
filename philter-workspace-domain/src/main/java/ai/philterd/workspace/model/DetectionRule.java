/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A rule made from reviewers' decisions: a value Philter should never flag again, or always flag. A confirmed
 * ({@link #STATUS_ACTIVE}) rule is a term on the never-redact or always-redact list of the Philter user whose key
 * Philter Workspace redacts batches with, so it applies to every batch from the next document on.
 *
 * <p>{@link #text} is the value itself and is encrypted at rest. {@link #textKey} is a keyed hash of its
 * lower-cased form, since Philter matches the lists' terms without regard to case, so rules for the same value
 * can be found without decrypting every row.
 */
@Document(collection = "detection_rules")
public class DetectionRule {

    /** Never flag the value: a term on Philter's never-redact list. */
    public static final String KIND_NEVER = "NEVER";
    /** Always flag the value: a term on Philter's always-redact list. */
    public static final String KIND_ALWAYS = "ALWAYS";

    /** Waiting for someone allowed to confirm rules to confirm or dismiss it. Not yet in Philter. */
    public static final String STATUS_PROPOSED = "PROPOSED";
    /** Confirmed and written to Philter. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** Dismissed; kept so the same value is not proposed again. */
    public static final String STATUS_DISMISSED = "DISMISSED";

    /** Made by a reviewer from a span. */
    public static final String ORIGIN_REVIEWER = "REVIEWER";
    /** Proposed by Philter Workspace after repeated refusals in a batch. */
    public static final String ORIGIN_REFUSALS = "REFUSALS";

    @Id
    private String id;
    private String kind;
    private String status;
    private String origin;
    private String text;
    @Indexed
    private String textKey;
    /** The PII type of the span the rule was made from. Philter's lists are not typed. */
    private String piiType;
    private String documentId;
    private String batchId;
    /** For a rule proposed after refusals, how many refusals there were when it was proposed. */
    private int refusals;
    private String proposedBy;
    private Instant proposedAt;
    private String decidedBy;
    private Instant decidedAt;
    /**
     * Whether confirming the rule added its term to Philter's list. False when the term was already there, put
     * there by someone else, so removing the rule leaves it.
     */
    private boolean addedToPhilter;

    public DetectionRule() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(final String kind) { this.kind = kind; }

    public String getStatus() { return status; }
    public void setStatus(final String status) { this.status = status; }

    public String getOrigin() { return origin; }
    public void setOrigin(final String origin) { this.origin = origin; }

    public String getText() { return text; }
    public void setText(final String text) { this.text = text; }

    public String getTextKey() { return textKey; }
    public void setTextKey(final String textKey) { this.textKey = textKey; }

    public String getPiiType() { return piiType; }
    public void setPiiType(final String piiType) { this.piiType = piiType; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(final String documentId) { this.documentId = documentId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(final String batchId) { this.batchId = batchId; }

    public int getRefusals() { return refusals; }
    public void setRefusals(final int refusals) { this.refusals = refusals; }

    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(final String proposedBy) { this.proposedBy = proposedBy; }

    public Instant getProposedAt() { return proposedAt; }
    public void setProposedAt(final Instant proposedAt) { this.proposedAt = proposedAt; }

    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(final String decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(final Instant decidedAt) { this.decidedAt = decidedAt; }

    public boolean isAddedToPhilter() { return addedToPhilter; }
    public void setAddedToPhilter(final boolean addedToPhilter) { this.addedToPhilter = addedToPhilter; }

    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
    public boolean isProposed() { return STATUS_PROPOSED.equals(status); }
}
