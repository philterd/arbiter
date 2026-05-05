/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tamper-evident summary captured when a document is finalized. Persists the document
 * identity, a SHA-256 hash of the original text, the reviewers who approved it (with
 * timestamps), and every overturn recorded against the document's spans during review.
 */
@Document(collection = "redaction_certificates")
public class RedactionCertificate {

    @Id
    private String id;

    @Indexed
    private String documentId;
    private String documentFilename;

    /** SHA-256 of the document's original text, hex-encoded. */
    private String documentHash;

    private Instant finalizedAt;
    private String finalizedBy;

    private List<ApprovalEntry> reviewers = new ArrayList<>();
    private List<OverturnEntry> overturns = new ArrayList<>();

    public RedactionCertificate() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(final String documentId) { this.documentId = documentId; }

    public String getDocumentFilename() { return documentFilename; }
    public void setDocumentFilename(final String documentFilename) { this.documentFilename = documentFilename; }

    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(final String documentHash) { this.documentHash = documentHash; }

    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(final Instant finalizedAt) { this.finalizedAt = finalizedAt; }

    public String getFinalizedBy() { return finalizedBy; }
    public void setFinalizedBy(final String finalizedBy) { this.finalizedBy = finalizedBy; }

    public List<ApprovalEntry> getReviewers() {
        if (reviewers == null) reviewers = new ArrayList<>();
        return reviewers;
    }
    public void setReviewers(final List<ApprovalEntry> reviewers) {
        this.reviewers = reviewers == null ? new ArrayList<>() : reviewers;
    }

    public List<OverturnEntry> getOverturns() {
        if (overturns == null) overturns = new ArrayList<>();
        return overturns;
    }
    public void setOverturns(final List<OverturnEntry> overturns) {
        this.overturns = overturns == null ? new ArrayList<>() : overturns;
    }

    /** A single reviewer approval recorded on the document. */
    public static class ApprovalEntry {
        private String reviewerEmail;
        private Instant approvedAt;

        public ApprovalEntry() {
        }

        public ApprovalEntry(final String reviewerEmail, final Instant approvedAt) {
            this.reviewerEmail = reviewerEmail;
            this.approvedAt = approvedAt;
        }

        public String getReviewerEmail() { return reviewerEmail; }
        public void setReviewerEmail(final String reviewerEmail) { this.reviewerEmail = reviewerEmail; }

        public Instant getApprovedAt() { return approvedAt; }
        public void setApprovedAt(final Instant approvedAt) { this.approvedAt = approvedAt; }
    }

    /** A single overturn event captured from the audit log. */
    public static class OverturnEntry {
        private String spanId;
        private String spanText;
        private String spanType;
        private String previousStatus;
        private String newStatus;
        private String previousActor;
        private String overturnedBy;
        private Instant overturnedAt;
        private String reason;

        public OverturnEntry() {
        }

        public String getSpanId() { return spanId; }
        public void setSpanId(final String spanId) { this.spanId = spanId; }

        public String getSpanText() { return spanText; }
        public void setSpanText(final String spanText) { this.spanText = spanText; }

        public String getSpanType() { return spanType; }
        public void setSpanType(final String spanType) { this.spanType = spanType; }

        public String getPreviousStatus() { return previousStatus; }
        public void setPreviousStatus(final String previousStatus) { this.previousStatus = previousStatus; }

        public String getNewStatus() { return newStatus; }
        public void setNewStatus(final String newStatus) { this.newStatus = newStatus; }

        public String getPreviousActor() { return previousActor; }
        public void setPreviousActor(final String previousActor) { this.previousActor = previousActor; }

        public String getOverturnedBy() { return overturnedBy; }
        public void setOverturnedBy(final String overturnedBy) { this.overturnedBy = overturnedBy; }

        public Instant getOverturnedAt() { return overturnedAt; }
        public void setOverturnedAt(final Instant overturnedAt) { this.overturnedAt = overturnedAt; }

        public String getReason() { return reason; }
        public void setReason(final String reason) { this.reason = reason; }
    }
}
