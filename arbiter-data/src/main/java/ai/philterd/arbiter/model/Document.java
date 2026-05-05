package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;

@org.springframework.data.mongodb.core.mapping.Document(collection = "documents")
public class Document {

    @Id
    private String id;
    private String batchId;
    private String filename;
    private String storagePath;
    private String originalText;
    private String status;
    private double riskScore;
    private String philterContextId;
    private LocalDateTime createdAt;
    private LocalDateTime statusChangedAt;
    private String failureMessage;
    /**
     * Emails of reviewers who have approved this document, in order of first → last approval.
     * Used to count progress against the batch's approval-rule requirement and to enforce that
     * the same reviewer cannot approve a document twice.
     */
    private java.util.List<String> approvedBy = new java.util.ArrayList<>();

    /**
     * Stable random number in [0, 1) generated once during persistence. Used by the
     * dual-approval sampling rule so the same document keeps the same sampling decision
     * across reads. {@code null} means the document predates the sampling roll — the rule
     * conservatively treats it as not sampled in.
     */
    private Double dualApprovalSamplingRoll;

    public Document() {
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(final LocalDateTime statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    /** Set the status and its change timestamp atomically. */
    public void changeStatus(final String newStatus) {
        this.status = newStatus;
        this.statusChangedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(final String batchId) {
        this.batchId = batchId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(final String filename) {
        this.filename = filename;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(final String storagePath) {
        this.storagePath = storagePath;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(final String originalText) {
        this.originalText = originalText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(final double riskScore) {
        this.riskScore = riskScore;
    }

    public String getPhilterContextId() {
        return philterContextId;
    }

    public void setPhilterContextId(final String philterContextId) {
        this.philterContextId = philterContextId;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(final String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public java.util.List<String> getApprovedBy() {
        if (approvedBy == null) approvedBy = new java.util.ArrayList<>();
        return approvedBy;
    }

    public void setApprovedBy(final java.util.List<String> approvedBy) {
        this.approvedBy = approvedBy == null ? new java.util.ArrayList<>() : approvedBy;
    }

    public Double getDualApprovalSamplingRoll() { return dualApprovalSamplingRoll; }
    public void setDualApprovalSamplingRoll(final Double dualApprovalSamplingRoll) {
        this.dualApprovalSamplingRoll = dualApprovalSamplingRoll;
    }
}
