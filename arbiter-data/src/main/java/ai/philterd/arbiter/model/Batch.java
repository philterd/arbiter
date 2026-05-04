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
    private boolean closed;
    private LocalDateTime closedAt;
    private String closedBy;

    public Batch() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Map<String, Object> getStats() {
        return stats;
    }

    public void setStats(Map<String, Object> stats) {
        this.stats = stats;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getDocumentThreshold() {
        return documentThreshold;
    }

    public void setDocumentThreshold(double documentThreshold) {
        this.documentThreshold = documentThreshold;
    }

    public double getAuditSamplingRate() {
        return auditSamplingRate;
    }

    public void setAuditSamplingRate(double auditSamplingRate) {
        this.auditSamplingRate = auditSamplingRate;
    }

    public Map<String, Integer> getPiiTypeWeights() {
        return piiTypeWeights;
    }

    public void setPiiTypeWeights(Map<String, Integer> piiTypeWeights) {
        this.piiTypeWeights = piiTypeWeights;
    }

    public String getWeightSetId() {
        return weightSetId;
    }

    public void setWeightSetId(String weightSetId) {
        this.weightSetId = weightSetId;
    }

    public String getPhilterInstanceId() {
        return philterInstanceId;
    }

    public void setPhilterInstanceId(String philterInstanceId) {
        this.philterInstanceId = philterInstanceId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }
}
