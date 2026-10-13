/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.AuditLog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Filtered reads of the audit log.
 */
public interface AuditLogQueryService {

    List<AuditLog> find(Instant start, Instant end, String userEmail, String resourceType, String resourceId, int limit);

    /**
     * Paged investigation query for the Admin → Audit Log page. Supports the same
     * filter set as {@link #find} plus action and outcome, sorted newest-first
     * (the natural order an investigator wants when chasing what happened most
     * recently). Returns the page slice and the total matching count so the
     * caller can render pager controls.
     */
    Result query(QueryFilters filters, int page, int pageSize);

    /**
     * Fetches all audit entries for a document and its spans in a single query,
     * sorted newest-first by the database.
     */
    List<AuditLog> findForDocument(String documentId, Collection<String> spanIds);

    record QueryFilters(Instant start,
                               Instant end,
                               String userEmail,
                               String action,
                               String resourceType,
                               String resourceId,
                               String outcome,
                               String ipAddress) {
    }

    record Result(List<AuditLog> entries, long total, int page, int pageSize) {
    }
}
