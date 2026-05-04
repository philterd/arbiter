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
}
