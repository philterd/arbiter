/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.store.sqlite;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.service.AuditLogQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "arbiter", name = "store", havingValue = "sqlite")
public class SqliteAuditLogQueryService implements AuditLogQueryService {

    private final SqliteDocumentStore store;

    public SqliteAuditLogQueryService(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    public List<AuditLog> find(final Instant start, final Instant end, final String userEmail,
                               final String resourceType, final String resourceId, final int limit) {
        final List<Where> clauses = timeRange(start, end);
        if (notBlank(userEmail)) clauses.add(Where.eq("userEmail", userEmail.trim()));
        if (notBlank(resourceType)) clauses.add(Where.eq("resourceType", resourceType.trim()));
        if (notBlank(resourceId)) clauses.add(Where.eq("resourceId", resourceId.trim()));
        return store.find(AuditLog.class, Where.and(clauses), Sort.by(Sort.Direction.ASC, "timestamp"),
                limit > 0 ? limit : -1, 0);
    }

    @Override
    public Result query(final QueryFilters f, final int page, final int pageSize) {
        final int safePage = Math.max(0, page);
        final int safeSize = pageSize <= 0 ? 25 : Math.min(pageSize, 200);
        final List<Where> clauses = timeRange(f.start(), f.end());
        if (notBlank(f.userEmail())) clauses.add(Where.eq("userEmail", f.userEmail().trim()));
        if (notBlank(f.action())) clauses.add(Where.eq("action", f.action().trim()));
        if (notBlank(f.resourceType())) clauses.add(Where.eq("resourceType", f.resourceType().trim()));
        if (notBlank(f.resourceId())) clauses.add(Where.eq("resourceId", f.resourceId().trim()));
        if (notBlank(f.outcome())) clauses.add(Where.eq("outcome", f.outcome().trim()));
        if (notBlank(f.ipAddress())) clauses.add(Where.eq("ipAddress", f.ipAddress().trim()));
        final Where where = Where.and(clauses);
        final long total = store.count(AuditLog.class, where);
        final List<AuditLog> entries = store.find(AuditLog.class, where, Sort.by(Sort.Direction.DESC, "timestamp"),
                safeSize, (long) safePage * safeSize);
        return new Result(entries, total, safePage, safeSize);
    }

    @Override
    public List<AuditLog> findForDocument(final String documentId, final Collection<String> spanIds) {
        final List<Where> ors = new ArrayList<>();
        ors.add(Where.and(Where.eq("resourceType", "Document"), Where.eq("resourceId", documentId)));
        if (spanIds != null && !spanIds.isEmpty()) {
            ors.add(Where.and(Where.eq("resourceType", "Span"), Where.in("resourceId", spanIds)));
        }
        return store.find(AuditLog.class, Where.or(ors), Sort.by(Sort.Direction.DESC, "timestamp"), -1, 0);
    }

    private static List<Where> timeRange(final Instant start, final Instant end) {
        final List<Where> clauses = new ArrayList<>();
        if (start != null) clauses.add(Where.cmp("timestamp", ">=", start));
        if (end != null) clauses.add(Where.cmp("timestamp", "<=", end));
        return clauses;
    }

    private static boolean notBlank(final String s) {
        return s != null && !s.isBlank();
    }
}
