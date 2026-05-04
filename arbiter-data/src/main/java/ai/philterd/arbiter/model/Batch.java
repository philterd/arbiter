package ai.philterd.arbiter.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "batches")
public class Batch {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;
    private LocalDateTime createdAt;
    private String ownerId;
    private String groupId;
    private Map<String, Object> stats;
    private double confidenceThreshold = 0.8;
    private double documentThreshold = 0.25;
    private double auditSamplingRate = 0.10;
    private Map<String, Integer> piiTypeWeights;
    private String weightSetId;
    private String philterInstanceId;
    private String policyName;
    private String domain;
    private String context = "";
    private boolean closed;
    private LocalDateTime closedAt;
    private String closedBy;

    public Batch() {
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(final String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(final String groupId) {
        this.groupId = groupId;
    }

    public Map<String, Object> getStats() {
        return stats;
    }

    public void setStats(final Map<String, Object> stats) {
        this.stats = stats;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(final double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getDocumentThreshold() {
        return documentThreshold;
    }

    public void setDocumentThreshold(final double documentThreshold) {
        this.documentThreshold = documentThreshold;
    }

    public double getAuditSamplingRate() {
        return auditSamplingRate;
    }

    public void setAuditSamplingRate(final double auditSamplingRate) {
        this.auditSamplingRate = auditSamplingRate;
    }

    public Map<String, Integer> getPiiTypeWeights() {
        return piiTypeWeights;
    }

    public void setPiiTypeWeights(final Map<String, Integer> piiTypeWeights) {
        this.piiTypeWeights = piiTypeWeights;
    }

    public String getWeightSetId() {
        return weightSetId;
    }

    public void setWeightSetId(final String weightSetId) {
        this.weightSetId = weightSetId;
    }

    public String getPhilterInstanceId() {
        return philterInstanceId;
    }

    public void setPhilterInstanceId(final String philterInstanceId) {
        this.philterInstanceId = philterInstanceId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getContext() {
        return context == null ? "" : context;
    }

    public void setContext(final String context) {
        this.context = context == null ? "" : context;
    }

    public void setPolicyName(final String policyName) {
        this.policyName = policyName;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(final String domain) {
        this.domain = domain;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(final boolean closed) {
        this.closed = closed;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(final LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(final String closedBy) {
        this.closedBy = closedBy;
    }
}
