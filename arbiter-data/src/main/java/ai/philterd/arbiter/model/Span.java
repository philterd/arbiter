package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "spans")
public class Span {

    @Id
    private String id;
    private String documentId;
    private String type;
    private String text;
    private double confidence;
    private String status;
    private Location location;
    private boolean manuallyCreated;
    private LocalDateTime createdAt;
    private LocalDateTime statusChangedAt;

    public Span() {
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(final String documentId) {
        this.documentId = documentId;
    }

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(final String text) {
        this.text = text;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(final double confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(final Location location) {
        this.location = location;
    }

    public boolean isManuallyCreated() {
        return manuallyCreated;
    }

    public void setManuallyCreated(final boolean manuallyCreated) {
        this.manuallyCreated = manuallyCreated;
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

    /** Set both the status and its change timestamp atomically. */
    public void changeStatus(final String newStatus) {
        this.status = newStatus;
        this.statusChangedAt = LocalDateTime.now();
    }
}
