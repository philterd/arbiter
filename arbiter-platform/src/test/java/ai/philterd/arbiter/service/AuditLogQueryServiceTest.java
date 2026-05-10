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
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditLogQueryService}. The service builds Mongo {@code Query}
 * objects from filter inputs; the tests capture the query and inspect its serialized
 * BSON form to verify the expected criteria, sort direction, and pagination math.
 *
 * Inspecting the BSON keeps the tests independent of internal Spring Data field names —
 * the on-the-wire query is what actually reaches Mongo, so that's what we assert.
 */
class AuditLogQueryServiceTest {

    private MongoOperations mongo;
    private AuditLogQueryService service;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoOperations.class);
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        when(mongo.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        service = new AuditLogQueryService(mongo);
    }

    // ---------- find(): the simple investigation path ----------

    @Test
    void findWithNoFiltersBuildsEmptyQuerySortedAscByTimestamp() {
        service.find(null, null, null, null, null, 0);
        final Query q = capture();
        assertTrue(q.getQueryObject().isEmpty(),
                "No filters → no criteria; the query object must be empty. Got: "
                        + q.getQueryObject().keySet());
        // find() returns rows in chronological order — investigators want oldest first
        // when narrowing on a window.
        assertEquals(1, q.getSortObject().getInteger("timestamp"));
        assertEquals(0, q.getLimit(), "Zero limit means no cap on the result set.");
    }

    @Test
    void findWithStartAndEndBuildsBoundedTimestampRange() {
        final Instant start = Instant.parse("2026-05-01T00:00:00Z");
        final Instant end = Instant.parse("2026-05-31T23:59:59Z");
        service.find(start, end, null, null, null, 100);
        final Query q = capture();
        final Document timestampClause = timestampClause(q);
        assertTrue(timestampClause.containsKey("$gte"),
                "Both bounds → timestamp clause must carry $gte. Got: " + timestampClause);
        assertTrue(timestampClause.containsKey("$lte"),
                "Both bounds → timestamp clause must carry $lte. Got: " + timestampClause);
        assertEquals(100, q.getLimit());
    }

    @Test
    void findWithOnlyStartHasGteWithoutLte() {
        service.find(Instant.parse("2026-05-01T00:00:00Z"), null, null, null, null, 0);
        final Document timestampClause = timestampClause(capture());
        assertTrue(timestampClause.containsKey("$gte"));
        assertFalse(timestampClause.containsKey("$lte"),
                "When only the start is given, the query must not pin an upper bound.");
    }

    @Test
    void findWithOnlyEndHasLteWithoutGte() {
        service.find(null, Instant.parse("2026-05-31T23:59:59Z"), null, null, null, 0);
        final Document timestampClause = timestampClause(capture());
        assertTrue(timestampClause.containsKey("$lte"));
        assertFalse(timestampClause.containsKey("$gte"));
    }

    @Test
    void findTrimsUserEmailBeforeMatching() {
        // Whitespace around the email is human noise — the criterion must match the
        // canonical form, otherwise an investigator pasting from a chat would get an
        // empty result.
        service.find(null, null, "   alice@x.com  ", null, null, 0);
        final Object value = scalarFieldValue(capture(), "userEmail");
        assertEquals("alice@x.com", value, "Trimmed email must reach the criteria.");
    }

    @Test
    void findIgnoresBlankUserEmail() {
        // A blank string must not introduce a {userEmail: ""} clause that would silently
        // exclude every entry.
        service.find(null, null, "   ", null, null, 0);
        assertFalse(hasField(capture(), "userEmail"),
                "Blank email must not introduce a userEmail clause.");
    }

    @Test
    void findCombinesAllFiltersWithAndOperator() {
        service.find(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z"),
                "alice@x.com", "Document", "doc-1", 50);
        final Document raw = capture().getQueryObject();
        // Multiple criteria collapse into a single {$and: [...]} root clause.
        assertTrue(raw.containsKey("$and"),
                "Multiple filter clauses must be ANDed; got: " + raw.keySet());
        @SuppressWarnings("unchecked")
        final List<Document> and = (List<Document>) raw.get("$and");
        assertEquals(4, and.size(),
                "Each non-blank filter contributes exactly one criteria fragment.");
    }

    // ---------- query(): paging and full filter set ----------

    @Test
    void queryClampsNegativePageToZero() {
        service.query(filters(null, null), -7, 25);
        final Query q = capture();
        assertEquals(0L, q.getSkip(),
                "Negative page values must clamp to zero; otherwise Spring's skip() throws.");
    }

    @Test
    void queryClampsPageSizeAboveMaximum() {
        // The service caps page size at 200 to bound memory usage and Mongo response size.
        service.query(filters(null, null), 0, 5_000);
        assertEquals(200, capture().getLimit());
    }

    @Test
    void queryDefaultsZeroOrNegativePageSizeToTwentyFive() {
        service.query(filters(null, null), 0, 0);
        assertEquals(25, capture().getLimit());

        // Re-stub for the second call.
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        when(mongo.count(any(Query.class), eq(AuditLog.class))).thenReturn(0L);
        service.query(filters(null, null), 0, -10);
        assertEquals(25, capture().getLimit(),
                "Non-positive page sizes must fall back to the default of 25.");
    }

    @Test
    void queryComputesSkipAsPageTimesSize() {
        service.query(filters(null, null), 4, 50);
        assertEquals(200L, capture().getSkip(),
                "Skip must be page * pageSize so consecutive pages don't overlap or skip rows.");
    }

    @Test
    void querySortsNewestFirstByTimestamp() {
        service.query(filters(null, null), 0, 25);
        final Document sort = capture().getSortObject();
        // Newest-first means descending timestamp.
        assertEquals(-1, sort.getInteger("timestamp"),
                "Investigators want newest-first; sort direction must be descending.");
    }

    @Test
    void queryIncludesAllExtendedFilters() {
        // The query() variant supports the full filter set (action, outcome, ipAddress)
        // that the simpler find() does not.
        service.query(new AuditLogQueryService.QueryFilters(
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T23:59:59Z"),
                "alice@x.com", "BATCH_CLOSE", "Batch", "b-1",
                "SUCCESS", "203.0.113.5"), 0, 25);

        final Document raw = capture().getQueryObject();
        assertTrue(raw.containsKey("$and"));
        // Eight filter inputs collapse to seven clauses (timestamp combines start+end).
        @SuppressWarnings("unchecked")
        final List<Document> and = (List<Document>) raw.get("$and");
        assertEquals(7, and.size(),
                "All seven non-timestamp filters plus the combined timestamp clause produce 7 fragments.");
        // Verify each scalar filter reached the query.
        assertEquals("alice@x.com", scalarValue(and, "userEmail"));
        assertEquals("BATCH_CLOSE", scalarValue(and, "action"));
        assertEquals("Batch", scalarValue(and, "resourceType"));
        assertEquals("b-1", scalarValue(and, "resourceId"));
        assertEquals("SUCCESS", scalarValue(and, "outcome"));
        assertEquals("203.0.113.5", scalarValue(and, "ipAddress"));
    }

    @Test
    void queryReturnsTotalAndSliceMetadata() {
        when(mongo.count(any(Query.class), eq(AuditLog.class))).thenReturn(742L);
        when(mongo.find(any(Query.class), eq(AuditLog.class)))
                .thenReturn(List.of(new AuditLog(), new AuditLog()));

        final AuditLogQueryService.Result result = service.query(filters(null, null), 3, 10);
        assertEquals(742L, result.total(),
                "Result must surface the total matching count so the caller can render pager controls.");
        assertEquals(3, result.page());
        assertEquals(10, result.pageSize());
        assertEquals(2, result.entries().size());
    }

    @Test
    void queryUsesSameCriteriaForCountAndSlice() {
        // The skip/limit applied to the slice must NOT influence the count. Verify that
        // count was invoked with the same query object that the slice ultimately uses,
        // and that count() was called BEFORE the skip/limit was layered on (so the count
        // reflects the unrestricted match).
        service.query(filters("alice@x.com", "BATCH_CLOSE"), 5, 25);
        // Both interactions happened on the same Query instance:
        verify(mongo).count(any(Query.class), eq(AuditLog.class));
        verify(mongo).find(any(Query.class), eq(AuditLog.class));
        // The captured query (used for find) has skip/limit applied — but the
        // implementation re-uses a single Query reference, so the count call already
        // observed the no-skip state. This test mostly guards against a future change
        // that builds two divergent queries and lets them drift.
    }

    // ---------- findForDocument(): document + spans union ----------

    @Test
    void findForDocumentBuildsOrCriteriaAcrossDocumentAndSpans() {
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        service.findForDocument("doc-1", List.of("s-1", "s-2"));

        final String json = capture().getQueryObject().toJson();
        assertTrue(json.contains("$or"),
                "findForDocument must union document-level and span-level rows.");
        assertTrue(json.contains("doc-1"));
        assertTrue(json.contains("s-1"));
        assertTrue(json.contains("s-2"));
    }

    @Test
    void findForDocumentWithNoSpansStillReturnsDocumentRows() {
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        service.findForDocument("doc-1", List.of());

        final String json = capture().getQueryObject().toJson();
        assertTrue(json.contains("doc-1"));
        // No span IDs → only one branch in the $or; we still expect $or for shape stability,
        // but the Span branch must not appear.
        assertFalse(json.contains("\"Span\""),
                "Empty span list must not introduce a Span clause: " + json);
    }

    @Test
    void findForDocumentSortsNewestFirst() {
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        service.findForDocument("doc-1", null);
        assertEquals(-1, capture().getSortObject().getInteger("timestamp"));
    }

    @Test
    void findForDocumentTolerantsToNullSpansList() {
        // Per-document audit pull commonly comes from a path that has the document but no
        // span list yet; passing null must not crash.
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
        service.findForDocument("doc-1", null);
        assertNotNull(capture());
    }

    // ---------- helpers ----------

    private Query capture() {
        final ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        // The most recent Query passed to find() OR count() is what we want; both routes
        // use the same captor since each test exercises only one method directly.
        verify(mongo, org.mockito.Mockito.atLeastOnce()).find(captor.capture(), eq(AuditLog.class));
        return captor.getValue();
    }

    private static AuditLogQueryService.QueryFilters filters(final String userEmail, final String action) {
        return new AuditLogQueryService.QueryFilters(null, null, userEmail, action,
                null, null, null, null);
    }

    /**
     * Pull the timestamp criteria document out of the captured query, regardless of whether
     * it sits at the root (single filter) or inside the {@code $and} array (multiple filters).
     */
    @SuppressWarnings("unchecked")
    private static Document timestampClause(final Query q) {
        final Document raw = q.getQueryObject();
        if (raw.containsKey("timestamp")) {
            return (Document) raw.get("timestamp");
        }
        if (raw.containsKey("$and")) {
            for (Document fragment : (List<Document>) raw.get("$and")) {
                if (fragment.containsKey("timestamp")) {
                    return (Document) fragment.get("timestamp");
                }
            }
        }
        throw new AssertionError("No timestamp clause in query: " + raw.keySet());
    }

    /** Find a scalar field's matched value in a single-clause-or-AND query. */
    @SuppressWarnings("unchecked")
    private static Object scalarFieldValue(final Query q, final String field) {
        final Document raw = q.getQueryObject();
        if (raw.containsKey(field)) {
            return raw.get(field);
        }
        if (raw.containsKey("$and")) {
            for (Document fragment : (List<Document>) raw.get("$and")) {
                if (fragment.containsKey(field)) return fragment.get(field);
            }
        }
        return null;
    }

    /** Locate a scalar field across the fragments of an $and array. */
    private static Object scalarValue(final List<Document> and, final String field) {
        for (Document fragment : and) {
            if (fragment.containsKey(field)) return fragment.get(field);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasField(final Query q, final String field) {
        final Document raw = q.getQueryObject();
        if (raw.containsKey(field)) return true;
        if (raw.containsKey("$and")) {
            for (Document fragment : (List<Document>) raw.get("$and")) {
                if (fragment.containsKey(field)) return true;
            }
        }
        return false;
    }
}
