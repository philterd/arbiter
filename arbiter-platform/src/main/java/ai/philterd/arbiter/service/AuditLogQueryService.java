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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
public class AuditLogQueryService {

    private final MongoOperations mongoOperations;

    public AuditLogQueryService(final MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    public List<AuditLog> find(final Instant start,
                               final Instant end,
                               final String userEmail,
                               final String resourceType,
                               final String resourceId,
                               final int limit) {
        final List<Criteria> clauses = new ArrayList<>();
        if (start != null && end != null) {
            clauses.add(Criteria.where("timestamp").gte(start).lte(end));
        } else if (start != null) {
            clauses.add(Criteria.where("timestamp").gte(start));
        } else if (end != null) {
            clauses.add(Criteria.where("timestamp").lte(end));
        }
        if (userEmail != null && !userEmail.isBlank()) {
            clauses.add(Criteria.where("userEmail").is(userEmail.trim()));
        }
        if (resourceType != null && !resourceType.isBlank()) {
            clauses.add(Criteria.where("resourceType").is(resourceType.trim()));
        }
        if (resourceId != null && !resourceId.isBlank()) {
            clauses.add(Criteria.where("resourceId").is(resourceId.trim()));
        }

        final Query query = new Query();
        if (!clauses.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(clauses.toArray(new Criteria[0])));
        }
        query.with(Sort.by(Sort.Direction.ASC, "timestamp"));
        if (limit > 0) {
            query.limit(limit);
        }
        return mongoOperations.find(query, AuditLog.class);
    }

    /**
     * Paged investigation query for the Admin → Audit Log page. Supports the same
     * filter set as {@link #find} plus action and outcome, sorted newest-first
     * (the natural order an investigator wants when chasing what happened most
     * recently). Returns the page slice and the total matching count so the
     * caller can render pager controls.
     */
    public Result query(final QueryFilters filters, final int page, final int pageSize) {
        final int safePage = Math.max(0, page);
        final int safeSize = pageSize <= 0 ? 25 : Math.min(pageSize, 200);
        final List<Criteria> clauses = buildClauses(filters);

        final Query base = new Query();
        if (!clauses.isEmpty()) {
            base.addCriteria(new Criteria().andOperator(clauses.toArray(new Criteria[0])));
        }
        // count() ignores skip/limit, so the same Query the slice is built from
        // also serves as the count argument. The driver translates this into
        // {@code countDocuments(filter)} on the collection.
        final long total = mongoOperations.count(base, AuditLog.class);

        base.with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .skip((long) safePage * safeSize)
                .limit(safeSize);
        final List<AuditLog> entries = mongoOperations.find(base, AuditLog.class);
        return new Result(entries, total, safePage, safeSize);
    }

    private List<Criteria> buildClauses(final QueryFilters f) {
        final List<Criteria> clauses = new ArrayList<>();
        if (f.start() != null && f.end() != null) {
            clauses.add(Criteria.where("timestamp").gte(f.start()).lte(f.end()));
        } else if (f.start() != null) {
            clauses.add(Criteria.where("timestamp").gte(f.start()));
        } else if (f.end() != null) {
            clauses.add(Criteria.where("timestamp").lte(f.end()));
        }
        if (notBlank(f.userEmail())) clauses.add(Criteria.where("userEmail").is(f.userEmail().trim()));
        if (notBlank(f.action())) clauses.add(Criteria.where("action").is(f.action().trim()));
        if (notBlank(f.resourceType())) clauses.add(Criteria.where("resourceType").is(f.resourceType().trim()));
        if (notBlank(f.resourceId())) clauses.add(Criteria.where("resourceId").is(f.resourceId().trim()));
        if (notBlank(f.outcome())) clauses.add(Criteria.where("outcome").is(f.outcome().trim()));
        if (notBlank(f.ipAddress())) clauses.add(Criteria.where("ipAddress").is(f.ipAddress().trim()));
        return clauses;
    }

    private static boolean notBlank(final String s) {
        return s != null && !s.isBlank();
    }

    /** Filter inputs for {@link #query}. Any null or blank field is ignored. */
    public record QueryFilters(Instant start,
                               Instant end,
                               String userEmail,
                               String action,
                               String resourceType,
                               String resourceId,
                               String outcome,
                               String ipAddress) {
    }

    /** Page of results plus the total matching count. */
    public record Result(List<AuditLog> entries, long total, int page, int pageSize) {
    }

    /**
     * Fetches all audit entries for a document and its spans in a single query,
     * sorted newest-first by the database.
     */
    public List<AuditLog> findForDocument(final String documentId, final Collection<String> spanIds) {
        final List<Criteria> orClauses = new ArrayList<>();
        orClauses.add(Criteria.where("resourceType").is("Document").and("resourceId").is(documentId));
        if (spanIds != null && !spanIds.isEmpty()) {
            orClauses.add(Criteria.where("resourceType").is("Span").and("resourceId").in(spanIds));
        }
        final Query query = new Query(new Criteria().orOperator(orClauses.toArray(new Criteria[0])));
        query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        return mongoOperations.find(query, AuditLog.class);
    }
}
