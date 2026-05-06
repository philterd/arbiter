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
    private String description;
    private LocalDateTime createdAt;
    private String ownerId;
    private String groupId;
    private Map<String, Object> stats;
    private double confidenceThreshold = 0.8;
    private double documentThreshold = 0.25;
    private double auditSamplingRate = 0.10;
    /**
     * Approval rule sets configured on this batch. Rules within a set are AND-ed; the sets
     * themselves are OR-ed (any rule set whose rules all hold triggers dual approval).
     */
    private java.util.List<ApprovalRuleSet> approvalRuleSets = new java.util.ArrayList<>();

    // Legacy single-rule-set fields preserved for read-side migration of existing documents.
    // Newly saved batches write only to {@code approvalRuleSets}; on first read these fields
    // are folded into a synthetic rule set if the new field is empty.
    @Deprecated private java.util.Set<String> approvalRuleNames = new java.util.LinkedHashSet<>();
    @Deprecated private double riskScoreRuleThreshold = 0.9;
    @Deprecated private double rejectedConfidenceRuleThreshold = 0.95;
    @Deprecated private long experiencedReviewerRuleThreshold = 100;
    private Map<String, Integer> piiTypeWeights;
    private String weightSetId;
    private String philterInstanceId;
    private String policyName;
    private String domain;
    private String context = "";
    /**
     * Reference to a {@code FinalizationPolicy} that controls what happens to the
     * original document files when documents in this batch are finalized. Required
     * for new batches; pre-existing batches may be {@code null} until edited.
     */
    private String finalizationPolicyId;
    private String complianceProfileId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
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

    public java.util.List<ApprovalRuleSet> getApprovalRuleSets() {
        if (approvalRuleSets == null) approvalRuleSets = new java.util.ArrayList<>();
        return approvalRuleSets;
    }

    public void setApprovalRuleSets(final java.util.List<ApprovalRuleSet> approvalRuleSets) {
        this.approvalRuleSets = approvalRuleSets == null
                ? new java.util.ArrayList<>() : approvalRuleSets;
    }

    /**
     * Effective rule sets at read time: returns {@link #getApprovalRuleSets()} when non-empty,
     * otherwise synthesizes a single rule set from the legacy single-set fields so old documents
     * keep evaluating identically until they're saved through the new UI.
     */
    public java.util.List<ApprovalRuleSet> effectiveRuleSets() {
        final java.util.List<ApprovalRuleSet> sets = getApprovalRuleSets();
        if (!sets.isEmpty()) return sets;
        if (approvalRuleNames == null || approvalRuleNames.isEmpty()) {
            return java.util.List.of();
        }
        final ApprovalRuleSet legacy = new ApprovalRuleSet();
        legacy.setId(id == null ? "legacy" : id + "-legacy");
        legacy.setRules(new java.util.LinkedHashSet<>(approvalRuleNames));
        legacy.setRiskScoreThreshold(riskScoreRuleThreshold);
        legacy.setRejectedConfidenceThreshold(rejectedConfidenceRuleThreshold);
        legacy.setExperiencedReviewerThreshold(experiencedReviewerRuleThreshold);
        return java.util.List.of(legacy);
    }

    @Deprecated
    public java.util.Set<String> getApprovalRuleNames() {
        if (approvalRuleNames == null) approvalRuleNames = new java.util.LinkedHashSet<>();
        return approvalRuleNames;
    }

    @Deprecated
    public void setApprovalRuleNames(final java.util.Set<String> approvalRuleNames) {
        this.approvalRuleNames = approvalRuleNames == null
                ? new java.util.LinkedHashSet<>() : approvalRuleNames;
    }

    @Deprecated public double getRiskScoreRuleThreshold() { return riskScoreRuleThreshold; }
    @Deprecated public void setRiskScoreRuleThreshold(final double v) { this.riskScoreRuleThreshold = v; }

    @Deprecated public double getRejectedConfidenceRuleThreshold() { return rejectedConfidenceRuleThreshold; }
    @Deprecated public void setRejectedConfidenceRuleThreshold(final double v) { this.rejectedConfidenceRuleThreshold = v; }

    @Deprecated public long getExperiencedReviewerRuleThreshold() { return experiencedReviewerRuleThreshold; }
    @Deprecated public void setExperiencedReviewerRuleThreshold(final long v) { this.experiencedReviewerRuleThreshold = v; }

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

    public String getFinalizationPolicyId() {
        return finalizationPolicyId;
    }

    public void setFinalizationPolicyId(final String finalizationPolicyId) {
        this.finalizationPolicyId = finalizationPolicyId;
    }

    public String getComplianceProfileId() { return complianceProfileId; }
    public void setComplianceProfileId(final String complianceProfileId) { this.complianceProfileId = complianceProfileId; }

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
