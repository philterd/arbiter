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
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class GeneralSettings {

    public static final String SINGLETON_ID = "general";

    @Id
    private String id = SINGLETON_ID;

    private String workspaceUrl;
    private String timezone;
    private String opensearchEndpoint;
    /**
     * Master switch for the full-text search index. When {@code true}, Philter Workspace writes each
     * document's original, unredacted text to OpenSearch on ingest and serves the search page from that
     * index. Off by default: the index is an extra copy of the data Philter Workspace exists to protect, so
     * an administrator turns it on deliberately. A settings row saved before this field existed also reads
     * as off.
     */
    private boolean fullTextSearchEnabled = false;
    /** Optional HTTP basic-auth username for the full-text search OpenSearch cluster. */
    private String opensearchUsername;
    /**
     * Optional HTTP basic-auth password. Encrypted at rest by the
     * {@code SymmetricCipher} field-encryption callbacks (the value carries the
     * {@code enc:v1:} marker after a save and is decrypted transparently on read).
     */
    private String opensearchPassword;
    /**
     * Index name used for the full-text search. Defaults to {@code philter-workspace-documents}
     * applied at read time when the persisted value is unset.
     */
    private String opensearchIndexName;
    /**
     * Maximum size of a single uploaded document, in bytes. Enforced by the upload and ingest
     * endpoints regardless of which path is used. {@code 0} means unset (the service default
     * applies on read).
     */
    private long maxUploadFileSizeBytes;
    /**
     * Set once every user row has been rewritten without the password hash, MFA secret, and API key that
     * Philter Workspace's removed local sign-in kept ({@code LocalCredentialsCleanup}).
     */
    private boolean localCredentialsRemoved;
    /**
     * Maximum number of data-import jobs allowed to run concurrently across the whole
     * deployment. Bounded to 1–10 by the admin form; the {@link
     * ai.philterd.workspace.service.GeneralSettingsService} default of {@code 1} is
     * applied at read time when the persisted value is unset or out of range.
     */
    private int maxConcurrentDataImports;
    /**
     * Master switch for the data-source host allow-list. When {@code true} (the
     * default for new and legacy rows alike — see the field initializer below),
     * outbound connections to OpenSearch / Elasticsearch / Philter / Ollama
     * endpoints are gated by {@code DataSourceHostAllowList}. When {@code false},
     * the allow-list is bypassed entirely and any operator-supplied endpoint is
     * accepted. Disabling is documented as not recommended (Admin → Security
     * help text plus {@code docs/admin/security.md}) — it removes the SSRF
     * defence that protects internal infrastructure from admin-driven probing.
     *
     * <p>Initialized to {@code true} so legacy persisted rows that pre-date this
     * field load with the allow-list enabled.
     */
    private boolean hostAllowListEnabled = true;
    /**
     * Whether reviewers, and not only administrators, may confirm, dismiss, and remove detection rules
     * ({@link DetectionRule}). A reviewer's rule is otherwise a proposal until an administrator confirms it.
     */
    private boolean reviewersConfirmRules;
    /**
     * How many times a value must be refused in one batch's documents, and never accepted, before Philter
     * Workspace proposes never flagging it. {@code 0} proposes nothing.
     */
    private int ruleProposalRefusals = DEFAULT_RULE_PROPOSAL_REFUSALS;

    /**
     * The case-type mapping (#16): which Philter policy redacts documents of each case type, in batches that
     * choose their policy by case type. A list rather than a map, since a case type can contain characters a
     * MongoDB key cannot.
     */
    private java.util.List<CaseTypePolicy> caseTypePolicies = new java.util.ArrayList<>();

    public static final int DEFAULT_RULE_PROPOSAL_REFUSALS = 5;
    public static final int MAX_RULE_PROPOSAL_REFUSALS = 1000;

    public GeneralSettings() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getWorkspaceUrl() { return workspaceUrl; }
    public void setWorkspaceUrl(final String workspaceUrl) { this.workspaceUrl = workspaceUrl; }

    public String getTimezone() { return timezone; }
    public void setTimezone(final String timezone) { this.timezone = timezone; }

    public String getOpensearchEndpoint() { return opensearchEndpoint; }
    public void setOpensearchEndpoint(final String opensearchEndpoint) { this.opensearchEndpoint = opensearchEndpoint; }

    public boolean isFullTextSearchEnabled() { return fullTextSearchEnabled; }
    public void setFullTextSearchEnabled(final boolean fullTextSearchEnabled) {
        this.fullTextSearchEnabled = fullTextSearchEnabled;
    }

    public String getOpensearchUsername() { return opensearchUsername; }
    public void setOpensearchUsername(final String opensearchUsername) {
        this.opensearchUsername = opensearchUsername;
    }

    public String getOpensearchPassword() { return opensearchPassword; }
    public void setOpensearchPassword(final String opensearchPassword) {
        this.opensearchPassword = opensearchPassword;
    }

    public String getOpensearchIndexName() { return opensearchIndexName; }
    public void setOpensearchIndexName(final String opensearchIndexName) {
        this.opensearchIndexName = opensearchIndexName;
    }

    public long getMaxUploadFileSizeBytes() { return maxUploadFileSizeBytes; }
    public void setMaxUploadFileSizeBytes(final long maxUploadFileSizeBytes) {
        this.maxUploadFileSizeBytes = maxUploadFileSizeBytes;
    }

    public boolean isLocalCredentialsRemoved() { return localCredentialsRemoved; }
    public void setLocalCredentialsRemoved(final boolean localCredentialsRemoved) { this.localCredentialsRemoved = localCredentialsRemoved; }

    public int getMaxConcurrentDataImports() { return maxConcurrentDataImports; }
    public void setMaxConcurrentDataImports(final int maxConcurrentDataImports) {
        this.maxConcurrentDataImports = maxConcurrentDataImports;
    }

    public boolean isHostAllowListEnabled() { return hostAllowListEnabled; }
    public void setHostAllowListEnabled(final boolean hostAllowListEnabled) {
        this.hostAllowListEnabled = hostAllowListEnabled;
    }

    public boolean isReviewersConfirmRules() { return reviewersConfirmRules; }
    public void setReviewersConfirmRules(final boolean reviewersConfirmRules) {
        this.reviewersConfirmRules = reviewersConfirmRules;
    }

    public int getRuleProposalRefusals() { return ruleProposalRefusals; }
    public void setRuleProposalRefusals(final int ruleProposalRefusals) {
        this.ruleProposalRefusals = ruleProposalRefusals;
    }

    public java.util.List<CaseTypePolicy> getCaseTypePolicies() {
        if (caseTypePolicies == null) caseTypePolicies = new java.util.ArrayList<>();
        return caseTypePolicies;
    }
    public void setCaseTypePolicies(final java.util.List<CaseTypePolicy> caseTypePolicies) {
        this.caseTypePolicies = caseTypePolicies;
    }

    /** The policy the mapping names for a case type, or null. */
    public String policyForCaseType(final String caseType) {
        final String normalized = CaseMetadata.normalizeCaseType(caseType);
        if (normalized == null) return null;
        for (CaseTypePolicy entry : getCaseTypePolicies()) {
            if (normalized.equals(entry.getCaseType())) return entry.getPolicyName();
        }
        return null;
    }
}
