/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    /**
     * Audit entries whose action is in {@code actions} with timestamp in [start, end] (inclusive).
     * Spring Data Mongo's derived-query criteria builder can't compose two clauses on the same
     * field, so this uses {@code Between} (inclusive both ends) — the boundary inclusivity is a
     * one-instant difference that doesn't matter for day-granularity reporting filters.
     */
    java.util.List<AuditLog> findByTimestampBetweenAndActionIn(
            java.time.Instant start, java.time.Instant end, java.util.Collection<String> actions);

    /** Resource-scoped history, oldest-first, used to build per-resource activity views. */
    java.util.List<AuditLog> findByResourceTypeAndResourceIdOrderByTimestampAsc(
            String resourceType, String resourceId);
}
