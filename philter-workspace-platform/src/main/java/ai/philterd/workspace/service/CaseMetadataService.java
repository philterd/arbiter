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

import ai.philterd.workspace.model.CaseMetadata;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Changes a document's case metadata (#16) and audits the change. The audit entry names the fields that changed,
 * with the case type, fee status, and IFP values, and keyed hashes of the case number and filer, which can identify
 * people.
 */
@Service
public class CaseMetadataService {

    private final DocumentRepository documents;
    private final AuditLogService audit;

    public CaseMetadataService(final DocumentRepository documents, final AuditLogService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    /** Thrown when the document's metadata cannot change now. */
    public static class NotChangeableException extends RuntimeException {
        public NotChangeableException(final String message) {
            super(message);
        }
    }

    /**
     * Replaces the document's case metadata with {@code updated} (null clears it). The caller has checked access to
     * the document and validated {@code updated} with {@link CaseMetadata#of}.
     *
     * @return whether anything changed
     */
    public boolean update(final Document document, final CaseMetadata updated) {
        if ("FINALIZED".equals(document.getStatus())) {
            throw new NotChangeableException("The document is finalized, so its case metadata cannot change.");
        }
        // The queue worker holds its own copy while it redacts; an edit now would be overwritten or refused.
        if ("PENDING".equals(document.getStatus()) || "PROCESSING".equals(document.getStatus())) {
            throw new NotChangeableException("The document is waiting to be redacted. Change its case metadata once "
                    + "it has been.");
        }
        final CaseMetadata before = document.getCaseMetadata() == null ? new CaseMetadata()
                : document.getCaseMetadata();
        final CaseMetadata after = updated == null ? new CaseMetadata() : updated;

        final Map<String, Object> details = new LinkedHashMap<>();
        if (!Objects.equals(before.getCaseType(), after.getCaseType())) {
            details.put("previousCaseType", before.getCaseType() == null ? "" : before.getCaseType());
            details.put("caseType", after.getCaseType() == null ? "" : after.getCaseType());
        }
        if (!Objects.equals(before.getFeeStatus(), after.getFeeStatus())) {
            details.put("previousFeeStatus", before.getFeeStatus() == null ? "" : before.getFeeStatus());
            details.put("feeStatus", after.getFeeStatus() == null ? "" : after.getFeeStatus());
        }
        if (!Objects.equals(before.getIfp(), after.getIfp())) {
            details.put("previousIfp", before.getIfp() == null ? "" : before.getIfp());
            details.put("ifp", after.getIfp() == null ? "" : after.getIfp());
        }
        if (!Objects.equals(before.getCaseNumber(), after.getCaseNumber())) {
            details.put("caseNumberHash", audit.hashForAudit(after.getCaseNumber()));
        }
        if (!Objects.equals(before.getFiler(), after.getFiler())) {
            details.put("filerHash", audit.hashForAudit(after.getFiler()));
        }
        if (details.isEmpty()) {
            return false;
        }
        document.setCaseMetadata(updated == null || updated.hasNoValues() ? null : updated);
        documents.save(document);
        details.put("documentId", document.getId());
        details.put("status", document.getStatus() == null ? "" : document.getStatus());
        audit.log("DOCUMENT_CASE_METADATA_UPDATE", "Document", document.getId(), details);
        return true;
    }
}
