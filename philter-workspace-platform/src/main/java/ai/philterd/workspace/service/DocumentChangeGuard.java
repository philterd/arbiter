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

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules every change to a document under review follows (#71):
 * <ul>
 *   <li>No one changes a document while another person holds its review lock. API-key callers, who never take the
 *       lock, are refused too; an approver or administrator releases a held document first.</li>
 *   <li>A change to the document's spans clears the approvals recorded so far, so every approval covers the spans
 *       the document is finalized with.</li>
 * </ul>
 */
@Service
public class DocumentChangeGuard {

    private final DocumentRepository documentRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentChangeGuard(final DocumentRepository documentRepository, final AuditLogService auditLogService) {
        this(documentRepository, auditLogService, Clock.systemUTC());
    }

    DocumentChangeGuard(final DocumentRepository documentRepository, final AuditLogService auditLogService,
                        final Clock clock) {
        this.documentRepository = documentRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /** Who else holds the document's review lock, or null when no one else does. */
    public String heldByOther(final Document document, final String email) {
        if (document == null || !document.isLocked(clock.instant())) return null;
        final String holder = document.getLockedBy();
        return email != null && email.equalsIgnoreCase(holder) ? null : holder;
    }

    /** Refuses the change with 409 while another person holds the document's review lock. */
    public void requireNotHeldByOther(final Document document, final String email) {
        final String holder = heldByOther(document, email);
        if (holder != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, lockedMessage(holder));
        }
    }

    public static String lockedMessage(final String holder) {
        return holder + " is reviewing this document. Wait for them to finish, or ask an approver to release it.";
    }

    /**
     * Clears the approvals recorded so far after a change to the document's spans, and audits which were cleared.
     * Returns how many were cleared.
     */
    public int spansChanged(final Document document, final String actor, final String action,
                            final String spanId) {
        if (document == null || document.getApprovedBy() == null || document.getApprovedBy().isEmpty()) return 0;
        final List<String> cleared = new ArrayList<>(document.getApprovedBy());
        document.getApprovedBy().clear();
        documentRepository.save(document);
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", document.getId());
        details.put("change", action);
        details.put("spanId", spanId == null ? "" : spanId);
        details.put("clearedApprovals", cleared);
        details.put("actor", actor == null ? "" : actor);
        auditLogService.log("DOCUMENT_APPROVALS_CLEARED", "Document", document.getId(), details);
        return cleared.size();
    }
}
